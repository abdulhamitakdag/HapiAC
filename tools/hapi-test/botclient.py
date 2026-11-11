#!/usr/bin/env python3
"""HapiAC test istemcisi: cevrimdisi 1.21.8 (protokol 772) test sunucusuna iki ham-protokol botu sokar.
Kurban yerinde durur; saldirgan senaryoya gore hile benzeri vurus desenleri uretir.
Yalnizca yerel test sunucusu (127.0.0.1:25599) icindir."""
import math, os, random, socket, struct, sys, threading, time, zlib

HOST, PORT, PROTO = "127.0.0.1", 25599, 772


def varint(n):
    n &= 0xFFFFFFFF
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def mcstr(s):
    b = s.encode()
    return varint(len(b)) + b


class Buf:
    def __init__(self, data):
        self.d, self.i = data, 0

    def varint(self):
        n = shift = 0
        while True:
            b = self.d[self.i]
            self.i += 1
            n |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        return n - (1 << 32) if n & (1 << 31) else n

    def take(self, n):
        v = self.d[self.i:self.i + n]
        self.i += n
        return v

    def unpack(self, fmt):
        return struct.unpack(">" + fmt, self.take(struct.calcsize(">" + fmt)))


class Bot:
    def __init__(self, name):
        self.name = name
        self.sock = socket.create_connection((HOST, PORT))
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.threshold = -1
        self.state = "login"
        self.x = self.y = self.z = 0.0
        self.yaw = self.pitch = 0.0
        self.ready = False
        self.entities = {}  # uuid bytes -> entity id
        self.epos = {}  # entity id -> [x, y, z] (sunucunun bu istemciye bildirdigi son konum)
        self.rpos = {}  # entity id -> [x, y, z] (istemcinin 3 tickte enterpole ettigi, ekranda gorunen konum)
        self.lerp = {}  # entity id -> kalan enterpolasyon adimi
        self.pong_delay = 0.0  # saniye; >0 ise transaction yanitlari geciktirilir (backtrack/fakelag benzetimi)
        self.pongq = []
        self.plock = threading.Lock()
        self.pidc = {}  # paket kimligi -> alinan adet (HAPI_DEBUG ile yazdirilir)
        self.lock = threading.Lock()
        self.alive = True
        self.kick = None
        self.eid = None  # kendi varlik kimligi (diger botun gozunden ogrenilir)
        self.kb = None  # sunucudan gelen, henuz uygulanmamis savrulma (blok/tick)
        self.vel = [0.0, 0.0, 0.0]
        self.ground = True
        self.gy = 0.0  # uzerinde durulan zemin yuksekligi
        self.resync = False
        self.popped = False  # totemimiz patladi (entity event 35)

    # --- cerceveleme ---
    def send(self, pid, payload=b""):
        data = varint(pid) + payload
        if self.threshold >= 0:
            if len(data) >= self.threshold:
                data = varint(len(data)) + zlib.compress(data)
            else:
                data = varint(0) + data
        with self.lock:
            self.sock.sendall(varint(len(data)) + data)

    def _read_exact(self, n):
        out = b""
        while len(out) < n:
            chunk = self.sock.recv(n - len(out))
            if not chunk:
                raise ConnectionError("baglanti kapandi")
            out += chunk
        return out

    def recv(self):
        n = shift = 0
        while True:
            b = self._read_exact(1)[0]
            n |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        buf = Buf(self._read_exact(n))
        if self.threshold >= 0:
            dlen = buf.varint()
            rest = buf.d[buf.i:]
            buf = Buf(zlib.decompress(rest) if dlen else rest)
        return buf.varint(), buf

    # --- giris ---
    def login(self):
        self.send(0x00, varint(PROTO) + mcstr(HOST) + struct.pack(">H", PORT) + varint(2))
        self.send(0x00, mcstr(self.name) + bytes(16))
        threading.Thread(target=self.reader, daemon=True).start()

    def reader(self):
        try:
            while self.alive:
                pid, buf = self.recv()
                getattr(self, "on_" + self.state)(pid, buf)
        except Exception as e:  # noqa
            self.alive = False
            if self.kick is None:
                self.kick = repr(e)

    def on_login(self, pid, buf):
        if pid == 0x03:
            self.threshold = buf.varint()
        elif pid == 0x02:
            self.send(0x03)  # login acknowledged
            self.state = "config"
            self.send(0x00, mcstr("tr_tr") + bytes([8]) + varint(0) + b"\x01" + bytes([0x7F]) + varint(1) + b"\x00\x01" + varint(0))
        elif pid == 0x00:
            self.kick = "login: " + buf.d[buf.i:].decode(errors="replace")
            self.alive = False

    def on_config(self, pid, buf):
        if pid == 0x0E:  # select known packs -> hicbirini bilmiyoruz
            self.send(0x07, varint(0))
        elif pid == 0x04:
            self.send(0x04, buf.take(8))
        elif pid == 0x05:
            self.send(0x05, buf.take(4))
        elif pid == 0x03:
            self.send(0x03)
            self.state = "play"
        elif pid == 0x02:
            self.kick = "config: " + repr(buf.d[buf.i:][:200])
            self.alive = False

    def on_play(self, pid, buf):
        self.pidc[pid] = self.pidc.get(pid, 0) + 1
        if pid == 0x26:  # keep alive
            self.send(0x1B, buf.take(8))
        elif pid == 0x36:  # ping (Grim transaction)
            with self.plock:
                self.pongq.append((time.time() + self.pong_delay, buf.take(4)))
            self.flush_pongs()
        elif pid == 0x0B:  # chunk batch finished
            self.send(0x0A, struct.pack(">f", 20.0))
        elif pid == 0x41:  # konum senkronu
            tid = buf.varint()
            x, y, z, vx, vy, vz, yaw, pitch = buf.unpack("ddddddff")
            (flags,) = buf.unpack("i")
            self.x = x + (self.x if flags & 1 else 0)
            self.y = y + (self.y if flags & 2 else 0)
            self.z = z + (self.z if flags & 4 else 0)
            self.yaw = yaw + (self.yaw if flags & 8 else 0)
            self.pitch = pitch + (self.pitch if flags & 16 else 0)
            self.vel = [0.0, 0.0, 0.0]
            self.ground, self.gy = True, self.y
            self.resync = True  # isinlanmadan sonraki tick yere basildigi bildirilir (vanilla da boyle yapar)
            self.send(0x00, varint(tid))
            self.send(0x1E, struct.pack(">dddffB", self.x, self.y, self.z, self.yaw, self.pitch, 1))
            if not self.ready:
                self.ready = True
                self.send(0x2B)  # player loaded
        elif pid == 0x01:  # spawn entity
            eid = buf.varint()
            uuid = buf.take(16)
            self.entities[uuid] = eid
            buf.varint()
            self.epos[eid] = list(buf.unpack("ddd"))
            self.rpos[eid] = list(self.epos[eid])
        elif pid in (0x2E, 0x2F):  # varlik goreli hareket
            eid = buf.varint()
            p = self.epos.get(eid)
            if p is not None:
                dx, dy, dz = buf.unpack("hhh")
                p[0] += dx / 4096.0
                p[1] += dy / 4096.0
                p[2] += dz / 4096.0
                self.lerp[eid] = 3
        elif pid == 0x1F:  # varlik konum senkronu
            eid = buf.varint()
            if eid in self.epos:
                self.epos[eid] = list(buf.unpack("ddd"))
                self.lerp[eid] = 3
        elif pid == 0x1E:  # entity event
            (eid, status) = buf.unpack("ib")
            if eid == self.eid and status == 35:
                self.popped = True
        elif pid == 0x1C:
            self.kick = "play: " + repr(buf.d[buf.i:][:300])
            self.alive = False
        elif self.eid is not None and len(buf.d) - buf.i == len(varint(self.eid)) + 6 and buf.d[buf.i:].startswith(varint(self.eid)):
            # kendi varligimiz icin hiz (savrulma) paketi: kimlik + 3 short (1/8000 blok/tick)
            buf.varint()
            self.kb = [v / 8000.0 for v in buf.unpack("hhh")]
            if os.environ.get("HAPI_DEBUG"):
                print("savrulma paketi %02X %s" % (pid, [round(v, 3) for v in self.kb]), flush=True)

    # --- oyun ---
    def rotate(self, dyaw, dpitch=0.0):
        self.yaw = struct.unpack(">f", struct.pack(">f", self.yaw + dyaw))[0]
        self.pitch = max(-90.0, min(90.0, self.pitch + dpitch))
        self.send(0x1F, struct.pack(">ffB", self.yaw, self.pitch, 1))

    def attack(self, eid):
        self.send(0x19, varint(eid) + varint(1) + b"\x00")
        self.send(0x3C, varint(0))

    def swing(self):
        self.send(0x3C, varint(0))

    def move(self, x, z):
        self.x, self.z = x, z
        self.send(0x1D, struct.pack(">dddB", self.x, self.y, self.z, 1))

    def look_at(self, pos, jitter=0.0):
        dx, dz = pos[0] - self.x, pos[2] - self.z
        yaw = math.degrees(math.atan2(-dx, dz)) + (random.uniform(-jitter, jitter) if jitter else 0.0)
        d = (yaw - self.yaw + 180.0) % 360.0 - 180.0
        if abs(d) > 1e-4:
            self.rotate(d)

    def lerp_tick(self, eid):  # vanilla canli varlik enterpolasyonu: her tick kalan yolun 1/adim'i
        n = self.lerp.get(eid, 0)
        if n > 0:
            r, e = self.rpos[eid], self.epos[eid]
            for i in range(3):
                r[i] += (e[i] - r[i]) / n
            self.lerp[eid] = n - 1

    def use_block(self, x, y, z):  # bloga sag tik (sandik acmak icin); ust yuze, ortasina
        pos = ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)
        self.send(0x3F, varint(0) + struct.pack(">Q", pos) + varint(1) + struct.pack(">fff", 0.5, 1.0, 0.5) + b"\x00\x00" + varint(1))

    def place(self, x, y, z, face, cx=0.5, cy=0.5, cz=0.5):  # elde blokla bloga sag tik; face: 0 alt, 1 ust, 2 kuzey, 3 guney, 4 bati, 5 dogu
        pos = ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)
        self.seq = getattr(self, "seq", 1) + 1
        self.send(0x3F, varint(0) + struct.pack(">Q", pos) + varint(face) + struct.pack(">fff", cx, cy, cz) + b"\x00\x00" + varint(self.seq))
        self.send(0x3C, varint(0))

    def click(self, window, slot, button=0, mode=0):  # mode: 0 normal, 1 shift (quick move), 2 swap; degisen slot / imlec bildirilmez
        self.send(0x11, varint(window) + varint(0) + struct.pack(">hb", slot, button) + varint(mode) + varint(0) + b"\x00")

    def swap_offhand(self):  # F tusu
        self.send(0x28, varint(6) + struct.pack(">Q", 0) + b"\x00" + varint(0))

    def held(self, slot):
        self.send(0x34, struct.pack(">h", slot))

    def use_item(self):
        self.seq = getattr(self, "seq", 1) + 1
        self.send(0x40, varint(0) + varint(self.seq) + struct.pack(">ff", self.yaw, self.pitch))

    def input(self, flags):  # tus durumu: 1 ileri, 2 geri, 4 sol, 8 sag, 16 ziplama, 32 egilme, 64 kosma
        self.send(0x2A, bytes([flags]))

    def sprint(self, on):
        self.send(0x29, varint(self.eid) + varint(1 if on else 2) + varint(0))

    def physics(self):  # duz zeminde vanilla hareket adimi (carpisma yok) + konum paketi
        v = self.vel
        for i in range(3):
            if abs(v[i]) < 0.003:
                v[i] = 0.0
        was_ground = self.ground
        self.x += v[0]
        self.z += v[2]
        ny = self.y + v[1]
        if ny <= self.gy:
            ny, self.ground, v[1] = self.gy, True, 0.0
        else:
            self.ground = False
        self.y = ny
        f = 0.546 if was_ground else 0.91
        v[0] *= f
        v[2] *= f
        v[1] = (v[1] - 0.08) * 0.98
        if v[0] or v[2] or self.resync or not (was_ground and self.ground):  # duran istemci konum paketi yollamaz
            self.resync = False
            self.send(0x1D, struct.pack(">dddB", self.x, self.y, self.z, 1 if self.ground else 0))

    def flush_pongs(self):  # sirayi bozmadan, vadesi gelen yanitlari gonder
        with self.plock:
            now = time.time()
            while self.pongq and self.pongq[0][0] <= now:
                self.send(0x2C, self.pongq.pop(0)[1])

    def tick_end(self):
        self.send(0x0C)


def offline_uuid(name):
    import hashlib
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    h[6] = (h[6] & 0x0F) | 0x30
    h[8] = (h[8] & 0x3F) | 0x80
    return bytes(h)


def run(scenario, seconds):
    victim, attacker = Bot("HapiKurban"), Bot(sys.argv[3] if len(sys.argv) > 3 else "HapiSaldir")
    victim.login()
    time.sleep(1.5)
    attacker.login()
    victim2 = None
    if scenario == "gecis":  # ikinci kurban (sunucuda max-players >= 3 olmali)
        time.sleep(1.0)
        victim2 = Bot("HapiKurban2")
        victim2.login()
    t0 = time.time()
    while time.time() - t0 < 15 and not (victim.ready and attacker.ready):
        time.sleep(0.1)
    print("hazir:", victim.ready, attacker.ready, "kick:", victim.kick, attacker.kick, flush=True)
    if not (victim.ready and attacker.ready):
        return 1

    fifo = open(os.environ["HAPI_FIFO"], "w") if os.environ.get("HAPI_FIFO") else None
    if fifo is not None:  # onceki kosuda uzaklasan kurban saldirganin gorus alanina donsun (yoksa senaryo hic baslamaz)
        fifo.write("tp HapiKurban HapiSaldir\n")
        fifo.flush()
    target = None
    base = None
    nxt = [12]
    tap = [None, None]  # w-tap: [birakma ticki, yeniden basma ticki]
    jumped = False
    tick = 0
    start = time.time()
    next_tick = start
    while time.time() - start < seconds and attacker.alive and victim.alive:
        if target is None:
            target = attacker.entities.get(offline_uuid("HapiKurban"))
            if target is not None:
                print("hedef entity id:", target, flush=True)
                victim.eid = target
                attacker.eid = victim.entities.get(offline_uuid(attacker.name))
        if target is not None and tick > 60:
            t = tick - 60
            if scenario == "ritim":  # her 8 tickte bir vurus, hic iskalamadan, nisan hareketli
                attacker.rotate(4.0 if (t // 10) % 2 == 0 else -4.0, 0.5 if t % 2 else -0.5)
                if t % 8 == 0:
                    attacker.attack(target)
            elif scenario == "snap":  # vurus tickinde +40, sonraki tick -40
                if t % 10 == 0:
                    attacker.attack(target)
                    attacker.rotate(40.0)
                elif t % 10 == 1:
                    attacker.rotate(-40.0)
            elif scenario == "lineer":  # sabit 4 derece/tick, pitch sabit
                attacker.rotate(4.0)
                if t % 15 == 0:
                    attacker.attack(target)
            elif scenario == "eksen":  # yalniz yaw, degisken hiz (AimLinear'a takilmaz), pitch kilitli
                attacker.rotate(3.5 + (t % 7) * 0.9)
                if t % 12 == 0:
                    attacker.attack(target)
            elif scenario == "tetik":  # triggerbot: degisken aralik, hic iskalama, nisan izgarada ve hareketli
                attacker.rotate(random.choice([-9, -6, -4, 3, 5, 8]) * 0.45, random.choice([-3, -2, 1, 2, 3]) * 0.3)
                if t >= nxt[0]:
                    attacker.attack(target)
                    nxt[0] = t + random.choice([9, 10, 11, 12, 13, 14, 16, 19])
            elif scenario == "izgara":  # once izgarali isinma (hassasiyet ogrenilsin), sonra kesirli adimlar
                if t < 300:
                    attacker.rotate(random.choice([-9, -6, -4, 3, 5, 8, 11]) * 0.15, random.choice([-2, -1, 1, 2]) * 0.15)
                else:
                    attacker.rotate(random.uniform(1.2, 6.0) * random.choice([-1, 1]), random.uniform(-0.4, 0.4))
                if t >= nxt[0]:
                    attacker.attack(target) if random.random() < 0.8 else attacker.swing()
                    nxt[0] = t + random.choice([10, 11, 12, 13, 14, 15, 17, 20])
            elif scenario in ("kilit", "takip"):  # kurban yana gidip gelir; kilit=kusursuz nisan, takip=insan benzeri gurultu
                # Kurban saldirganin 3 blok onunde saga sola yurur (yurume hizinin altinda, gercek hareket paketleriyle).
                # Her tick `tp` ile tasimak ise yaramaz: sunucu o konumu diger oyunculara ~60 tickte bir yayinlar.
                # Kurban op olmali (grim.exempt) yoksa Grim bot fizigini geri ceker.
                base = (attacker.x, attacker.z + 3.0)
                if abs(victim.x - base[0]) > 2.0 or abs(victim.z - base[1]) > 0.5:
                    if fifo is not None and t % 20 == 1:
                        fifo.write("tp HapiKurban %.3f %.1f %.3f\n" % (base[0], victim.y, base[1]))
                        fifo.flush()
                else:
                    victim.move(base[0] + 1.5 * math.sin(t * 0.1), base[1])
                attacker.lerp_tick(target)
                pos = (attacker.epos if os.environ.get("HAPI_HAM") else attacker.rpos).get(target)
                if pos is not None:
                    attacker.look_at(pos, 0.0 if scenario == "kilit" else 4.0)
                if t >= nxt[0]:
                    attacker.attack(target) if (scenario == "kilit" or random.random() < 0.8) else attacker.swing()
                    nxt[0] = t + random.choice([10, 11, 12, 13, 14, 15, 17, 20])
            elif scenario in ("duvar", "acik"):  # kurban 3 blok onde sabit; duvar=araya tas duvar orulur, acik=duvar kaldirilir
                bx, bz = attacker.x, attacker.z + 3.0
                if fifo is not None and t % 20 == 1 and t < 100:
                    fx, fy, fz = math.floor(attacker.x), int(attacker.y), math.floor(attacker.z) + 1
                    fifo.write("tp HapiKurban %.3f %.1f %.3f\n" % (bx, victim.y, bz))
                    fifo.write("fill %d %d %d %d %d %d %s\n" % (fx - 1, fy, fz, fx + 1, fy + int(os.environ.get("HAPI_YUK", "2")), fz, os.environ.get("HAPI_BLOK", "stone") if scenario == "duvar" else "air"))
                    fifo.flush()
                attacker.lerp_tick(target)
                pos = attacker.rpos.get(target)
                if pos is not None and t > 100:
                    if t == 101 and os.environ.get("HAPI_PITCH"):
                        attacker.rotate(0.0, float(os.environ["HAPI_PITCH"]))
                    attacker.look_at(pos)
                    if t >= nxt[0]:
                        attacker.attack(target)
                        nxt[0] = t + random.choice([10, 11, 12, 13, 14, 15, 17, 20])
            elif scenario == "gecis":  # switch aura: 90 derece ayrik iki kurbana birer tick arayla vurus
                target2 = attacker.entities.get(offline_uuid("HapiKurban2"))
                if fifo is not None and t % 20 == 1 and t < 100:
                    fifo.write("gamemode creative HapiKurban2\n")
                    fifo.write("tp HapiKurban %.3f %.1f %.3f\n" % (attacker.x, victim.y, attacker.z + 3.0))
                    fifo.write("tp HapiKurban2 %.3f %.1f %.3f\n" % (attacker.x - 3.0, victim.y, attacker.z))
                    fifo.flush()
                if target2 is not None and t > 100:
                    phase = t % 30
                    if phase in (0, 1):
                        tid = target if phase == 0 else target2
                        pos = attacker.epos.get(tid)
                        if pos is not None:
                            attacker.look_at(pos)
                            attacker.attack(tid)
            elif scenario == "sandik":  # yanina sandik konur, acilir, acikken vurulur
                if fifo is not None and t % 20 == 1 and t < 60:
                    fifo.write("tp HapiKurban %.3f %.1f %.3f\n" % (attacker.x, victim.y, attacker.z + 3.0))
                    fifo.write("setblock %d %d %d chest\n" % (math.floor(attacker.x) + 1, int(attacker.y), math.floor(attacker.z)))
                    fifo.flush()
                pos = attacker.epos.get(target)
                if pos is not None and t > 100:
                    attacker.look_at(pos)
                    if t == 140:
                        attacker.use_block(math.floor(attacker.x) + 1, int(attacker.y), math.floor(attacker.z))
                    if t >= nxt[0]:
                        attacker.attack(target)
                        nxt[0] = t + random.choice([10, 11, 12, 13, 14, 15, 17, 20])
            elif scenario in ("gecikme", "sabitping"):  # 15 sn bos / 8 sn dovus; gecikme=yalniz dovuste +250 ms, sabitping=hep +250 ms
                if fifo is not None and t % 20 == 1 and t < 60:
                    fifo.write("tp HapiKurban %.3f %.1f %.3f\n" % (attacker.x, victim.y, attacker.z + 3.0))
                    fifo.flush()
                fighting = (t % 460) >= 300
                attacker.pong_delay = 0.25 if (fighting or scenario == "sabitping") else 0.0
                pos = attacker.epos.get(target)
                if pos is not None and fighting:
                    attacker.look_at(pos)
                    if t >= nxt[0]:
                        attacker.attack(target)
                        nxt[0] = t + random.choice([10, 11, 12, 13, 14, 15, 17, 20])
            elif scenario in ("wtap", "wtapinsan"):  # kosarak vurus + ileri tusunu birak/bas; wtap=hep ayni tick, wtapinsan=oynak
                if t == 1 and attacker.eid is not None:
                    attacker.sprint(True)
                    attacker.input(0x41)
                if t >= nxt[0]:
                    attacker.attack(target)
                    nxt[0] = t + random.choice([10, 11, 12, 13, 14, 15, 17, 20])
                    rel = t if scenario == "wtap" else t + random.choice([0, 1, 1, 2])
                    tap = [rel, rel + (2 if scenario == "wtap" else random.choice([1, 2, 2, 3, 4]))]
                if t == tap[0]:
                    attacker.input(0x40)
                if t == tap[1]:
                    attacker.input(0x41)
            elif scenario in ("ziplama", "ziplamainsan", "ziplamayok"):  # kurban savrulur; ziplama=her seferinde ayni tickte ziplar
                cyc = t % 40
                if fifo is not None and cyc == 1:
                    if t == 1:
                        base = (victim.x, victim.y, victim.z)
                        fx, fy, fz = math.floor(base[0]), int(base[1]), math.floor(base[2])
                        fifo.write("gamemode survival HapiKurban\ndifficulty peaceful\n")
                        fifo.write("fill %d %d %d %d %d %d stone\n" % (fx - 4, fy - 1, fz - 6, fx + 4, fy - 1, fz + 12))
                        fifo.write("fill %d %d %d %d %d %d air\n" % (fx - 4, fy, fz - 6, fx + 4, fy + 4, fz + 12))
                    fifo.write("tp HapiKurban %.3f %.1f %.3f\n" % base)
                    fifo.write("tp %s %.3f %.1f %.3f\n" % (attacker.name, base[0], base[1], base[2] - 2.5))
                    fifo.flush()
                if cyc == 20:
                    attacker.rotate(0.0, -attacker.pitch)
                    attacker.look_at((victim.x, victim.y, victim.z))  # /tp sonrasi varlik konumu izlenmiyor; kurbanin kendi konumu
                    attacker.attack(target)
                    if os.environ.get("HAPI_DEBUG"):
                        print("vurus t=%d saldirgan=(%.2f %.2f %.2f yaw=%.1f pitch=%.1f) kurban=(%.2f %.2f %.2f) gorulen=%s" % (t, attacker.x, attacker.y, attacker.z, attacker.yaw, attacker.pitch, victim.x, victim.y, victim.z, attacker.epos.get(target)), flush=True)
                kb, victim.kb = victim.kb, None
                if jumped:
                    victim.input(0x00)
                    jumped = False
                if kb is not None and victim.ground:
                    victim.vel = kb
                    if scenario == "ziplama" or (scenario == "ziplamainsan" and random.random() < 0.5):
                        victim.vel[1] = max(0.42, victim.vel[1])
                        victim.input(0x10)
                        jumped = True
                victim.physics()
            elif scenario == "kacak":  # hiz hilesi: kurban tick basina 0.9 blok kayar -> Simulation geri cekmesi (SETBACK logu testi)
                victim.move(victim.x + 0.9, victim.z)
            elif scenario in ("kopru", "kopruegil", "koprutik", "snapkoy", "snaptut", "snapinsan", "hizlikoy", "hizliinsan"):
                # Yapi modulu: insaatci kurbandir. Havadaki tek blogun ustune isinlanir, eline tas verilir.
                if fifo is not None and t == 1:
                    fifo.write("gamemode survival HapiKurban\nclear HapiKurban\ngive HapiKurban stone 64\nsetblock 20 -51 20 stone\ntp HapiKurban 20.5 -50 20.5\n")
                    fifo.flush()
                if t > 40:
                    k = t - 40
                    bx, by, bz = math.floor(victim.x), math.floor(victim.y - 1e-4), math.floor(victim.z)
                    if scenario.startswith("kopru"):  # 5 tickte bir blok, doguya; kopru=kusursuz, kopruegil=her blokta egilme, koprutik=bosa tiklamalar
                        if k % 5 == 0:
                            victim.place(bx, by, bz, 5, 1.0, 0.5, 0.5)
                        elif scenario == "koprutik" and k % 5 == 2 and random.random() < 0.5:
                            victim.place(bx, by, bz, 1, 0.5, 1.0, 0.5)  # ust yuze bosa tiklama
                        if scenario == "kopruegil":
                            if k % 5 == 4:
                                victim.input(32)
                            elif k % 5 == 1:
                                victim.input(0)
                        victim.move(victim.x + 0.2, victim.z)
                    elif scenario.startswith("snap"):  # 12 tickte bir: don + koy, sonra geri don
                        hold = 4 if scenario == "snaptut" else 1
                        if k % 12 == 0:
                            victim.rotate(60.0, 30.0)
                            victim.place(bx, by, bz, 1, 0.5, 1.0, 0.5)
                        elif k % 12 == hold:
                            if scenario == "snapinsan":  # insan: geri donus birkac derece sapar ve iki tike yayilir
                                victim.rotate(-40.0 + random.uniform(-6, 6), -20.0 + random.uniform(-4, 4))
                            else:
                                victim.rotate(-60.0, -30.0)
                        elif scenario == "snapinsan" and k % 12 == hold + 1:
                            victim.rotate(-20.0 + random.uniform(-3, 3), -10.0 + random.uniform(-2, 2))
                    elif scenario == "hizlikoy":  # her tick tam bir blok koyma paketi
                        victim.place(bx, by, bz, 1, 0.5, 1.0, 0.5)
                    else:  # hizliinsan: 14-16 tiklama/sn, tick basina 0-2 paket
                        for _ in range(random.choice([0, 1, 1, 1, 2])):
                            victim.place(bx, by, bz, 1, 0.5, 1.0, 0.5)
            elif scenario in ("totem", "totemsabit", "toteminsan", "totemspam"):
                # Envanter modulu: kurbanin totemi 60 tickte bir patlatilir, ana eldeki totemi F ile diger ele alir.
                # totem=ayni tick, totemsabit=hep 4 tick sonra, toteminsan=4-9 tick, totemspam=F tusuna surekli basar
                if fifo is not None and t == 1:
                    fifo.write("gamemode survival HapiKurban\n")
                    fifo.flush()
                ph = t % 60
                if fifo is not None and ph == 5:
                    fifo.write("item replace entity HapiKurban weapon.mainhand with totem_of_undying\nitem replace entity HapiKurban weapon.offhand with totem_of_undying\n")
                    fifo.flush()
                    victim.popped = False
                    nxt[0] = None
                elif fifo is not None and ph == 30:
                    fifo.write("damage HapiKurban 1000\n")
                    fifo.flush()
                if scenario == "totemspam":
                    if ph >= 22 and ph % 4 == 2 and ph < 50:
                        victim.swap_offhand()
                elif victim.popped and ph >= 30:
                    if nxt[0] is None:
                        nxt[0] = t + {"totem": 0, "totemsabit": 4}.get(scenario, random.randint(4, 9))
                    if t == nxt[0]:
                        victim.swap_offhand()
                        victim.popped = False
            elif scenario in ("sandikbosalt", "sandikcift", "sandikritim", "sandikseri", "sandikinsan"):
                # Envanter modulu: kurban yanindaki sandigi acar ve slotlara shift-tiklar.
                # bosalt=tek tickte 12 slot, cift=tek tik + 3 tick sonra 12 slot (vanilla shift+cift tik),
                # ritim=3 tickte bir, seri=her tick, insan=2-6 tick oynak
                cx, cy, cz = math.floor(victim.x) + 1, int(victim.y), math.floor(victim.z)
                if fifo is not None and t == 1:
                    fifo.write("gamemode survival HapiKurban\nsetblock %d %d %d chest\n" % (cx, cy, cz))
                    fifo.flush()
                if t == 40:
                    victim.use_block(cx, cy, cz)
                    nxt[0] = 60
                k = t - 60
                if k >= 0:
                    if scenario == "sandikbosalt" and k % 40 == 0:
                        for i in range(12):
                            victim.click(1, i, 0, 1)
                    elif scenario == "sandikcift" and k % 40 == 0:
                        victim.click(1, 14, 0, 1)
                    elif scenario == "sandikcift" and k % 40 == 3:
                        for i in range(12):
                            victim.click(1, i, 0, 1)
                    elif scenario in ("sandikritim", "sandikseri", "sandikinsan") and t >= nxt[0]:
                        victim.click(1, k % 27, 0, 1)
                        nxt[0] = t + (3 if scenario == "sandikritim" else 1 if scenario == "sandikseri" else random.randint(2, 6))
            elif scenario in ("sessiz", "sessizinsan"):
                # Envanter modulu: sessiz=tek tickte slot degistir + kullan + geri don; sessizinsan=ayni is uc ayri tickte
                if fifo is not None and t == 1:
                    fifo.write("gamemode survival HapiKurban\n")
                    fifo.flush()
                ph = t % 20
                if scenario == "sessiz":
                    if ph == 10:
                        victim.held(3)
                        victim.use_item()
                        victim.held(0)
                else:
                    if ph == 10:
                        victim.held(3)
                    elif ph == 11:
                        victim.use_item()
                    elif ph == 13:
                        victim.held(0)
            elif scenario in ("kristal", "kristalinsan", "kristalyavas", "tahmin"):
                # Kristal modulu: saldirganin yanina 10 tickte bir (hasar almaz) end kristali cagrilir.
                # kristal=gorundugu tick tek vurus, kristalinsan=surekli sol tik (bosa tiklamali), kristalyavas=5 tick sonra,
                # tahmin=henuz dogmamis varlik kimligine vurus
                if fifo is not None and t == 1:
                    fifo.write("gamemode survival HapiSaldir\n")
                    fifo.flush()
                    base = set(attacker.epos)
                if t > 20:
                    if fifo is not None and t % 10 == 0 and scenario != "tahmin":
                        fifo.write("kill @e[type=end_crystal]\nsummon end_crystal %.1f %.1f %.1f {Invulnerable:1b,ShowBottom:0b}\n" % (attacker.x + 2, attacker.y, attacker.z))
                        fifo.flush()
                    if scenario == "tahmin":
                        if t % 20 == 0:
                            attacker.attack(max(attacker.epos) + 40)
                    else:
                        fresh = [e for e in attacker.epos if e not in base]
                        if scenario == "kristalinsan":
                            if t % 2 == 0:
                                attacker.attack(fresh[0]) if fresh else attacker.swing()
                                base.update(fresh)
                        else:
                            for e in fresh:
                                seen = attacker.lerp.setdefault(("k", e), t)
                                if t - seen >= (0 if scenario == "kristal" else 5):
                                    attacker.attack(e)
                                    base.add(e)
            elif scenario == "mod":  # saldirgan harita modu kanali bildirir (hapi.client.alert-patterns: ["xaero"] ile ClientMod)
                if t == 1:
                    attacker.send(0x15, mcstr("minecraft:register") + b"xaerominimap:main\x00fabric:registry/sync\x00worldedit:cui")
            elif scenario == "spam":  # tick basina 2 vurus
                attacker.attack(target)
                attacker.attack(target)
            elif scenario == "insan":  # duzensiz aralik, ara sira iskalama, degisken donus
                attacker.rotate(random.choice([-40, -22, -9, -3, -1, 0, 1, 2, 5, 9, 17, 35]) * 0.15, random.choice([-6, -2, -1, 0, 1, 2, 5]) * 0.15)
                if t >= nxt[0]:
                    attacker.attack(target) if random.random() < 0.8 else attacker.swing()
                    nxt[0] = t + random.choice([10, 11, 12, 13, 13, 14, 15, 17, 20])
        if False:  # konum kalp atisi kapali (bot zamanlamasi Timer/NoFall setback'i uretiyordu)
            for b in (attacker, victim):
                b.send(0x1D, struct.pack(">dddB", b.x, b.y, b.z, 1))
        attacker.flush_pongs()
        attacker.tick_end()
        victim.tick_end()
        if victim2 is not None and victim2.ready and victim2.alive:
            victim2.tick_end()
        tick += 1
        next_tick += 0.05
        delay = next_tick - time.time()
        if delay > 0:
            time.sleep(delay)
    print("gorulen varliklar:", len(attacker.entities), "konum:", round(attacker.x,1), round(attacker.y,1), round(attacker.z,1), "kurban:", round(victim.x,1), round(victim.y,1), round(victim.z,1), flush=True)
    if os.environ.get("HAPI_DEBUG"):
        print("saldirgan paketleri:", " ".join("%02X:%d" % kv for kv in sorted(attacker.pidc.items())), flush=True)
    print("bitti tick=%d saldirgan_kick=%s kurban_kick=%s" % (tick, attacker.kick, victim.kick), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(run(sys.argv[1], float(sys.argv[2])))
