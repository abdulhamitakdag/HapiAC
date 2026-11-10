#!/usr/bin/env python3
"""HapiAC test istemcisi: cevrimdisi 1.21.8 (protokol 772) test sunucusuna iki ham-protokol botu sokar.
Kurban yerinde durur; saldirgan senaryoya gore hile benzeri vurus desenleri uretir.
Yalnizca yerel test sunucusu (127.0.0.1:25599) icindir."""
import math, random, socket, struct, sys, threading, time, zlib

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
        self.pidc = {}  # paket kimligi -> alinan adet (HAPI_DEBUG ile yazdirilir)
        self.lock = threading.Lock()
        self.alive = True
        self.kick = None

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
            self.send(0x2C, buf.take(4))
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
        elif pid == 0x1C:
            self.kick = "play: " + repr(buf.d[buf.i:][:300])
            self.alive = False

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
    t0 = time.time()
    while time.time() - t0 < 15 and not (victim.ready and attacker.ready):
        time.sleep(0.1)
    print("hazir:", victim.ready, attacker.ready, "kick:", victim.kick, attacker.kick, flush=True)
    if not (victim.ready and attacker.ready):
        return 1

    import os
    fifo = open(os.environ["HAPI_FIFO"], "w") if os.environ.get("HAPI_FIFO") else None
    target = None
    base = None
    nxt = [12]
    tick = 0
    start = time.time()
    next_tick = start
    while time.time() - start < seconds and attacker.alive and victim.alive:
        if target is None:
            target = attacker.entities.get(offline_uuid("HapiKurban"))
            if target is not None:
                print("hedef entity id:", target, flush=True)
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
        attacker.tick_end()
        victim.tick_end()
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
