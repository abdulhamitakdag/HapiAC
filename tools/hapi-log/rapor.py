#!/usr/bin/env python3
"""HapiAC flag logu raporu.

Kullanim:
    rapor.py [ad=]<dosya|dizin> [[ad=]<dosya|dizin> ...] [--gun YYYY-AA-GG] [--oyuncu AD] [--adet N]

Dizin verilirse icindeki flags-*.log dosyalarinin hepsi okunur. "ad=" on eki o kaynagi etiketler
(birden fazla sunucunun logu tek raporda karsilastirilir). Cikti duz metindir.

Log sutunlari (TSV): saat, oyuncu, check, ihlal, dunya, ping, surum, marka, ayrinti
Geri cekmeler "SETBACK" check adiyla gelir; ayrintida neden=, yer=, mesafe=, konum= bulunur.
Girisler "CLIENT" adiyla gelir; ayrintida istemcinin bildirdigi mod kanallari bulunur.
"""
import glob
import os
import re
import sys
from collections import Counter, defaultdict

HAPI = {"AimSnap", "AimLinear", "AimGrid", "AimTrack", "AimAxis", "AttackRhythm", "ClickRate", "HitAccuracy", "WallHit",
        "TargetSwitch", "InventoryHit", "CombatLag", "JumpReset", "SprintReset", "ScaffoldSnap", "FastPlace", "BridgePerfect",
        "ClientMod", "AutoTotem", "InventoryBurst", "InventoryRhythm", "SilentSwap", "CrystalSpeed", "AttackPredict"}


def oku(kaynaklar, gun):
    for etiket, yol in kaynaklar:
        dosyalar = sorted(glob.glob(os.path.join(yol, "flags-*.log"))) if os.path.isdir(yol) else [yol]
        for d in dosyalar:
            m = re.search(r"flags-(\d{4}-\d\d-\d\d)", d)
            tarih = m.group(1) if m else "?"
            if gun and tarih != gun:
                continue
            with open(d, encoding="utf-8", errors="replace") as f:
                for satir in f:
                    p = satir.rstrip("\n").split("\t")
                    if len(p) == 7:  # surum/marka sutunlarindan onceki bicim
                        p = p[:6] + ["?", "?"] + p[6:]
                    if len(p) < 9:
                        continue
                    yield {"kaynak": etiket, "tarih": tarih, "saat": p[0], "oyuncu": p[1], "check": p[2],
                           "vl": p[3], "dunya": p[4], "ping": p[5], "surum": p[6], "marka": p[7], "ayrinti": p[8]}


def tablo(baslik, satirlar, sutunlar):
    print("\n## " + baslik)
    if not satirlar:
        print("(kayit yok)")
        return
    gen = [max(len(str(s[i])) for s in [sutunlar] + satirlar) for i in range(len(sutunlar))]
    for s in [sutunlar, ["-" * g for g in gen]] + satirlar:
        print("  ".join(str(h).ljust(g) for h, g in zip(s, gen)).rstrip())


def yorum(adet, oyuncu, enbuyuk_pay):
    if oyuncu <= 3 and adet >= 20:
        return "az oyuncuda yogun: elle incele"
    if enbuyuk_pay >= 0.6 and adet >= 20:
        return "tek oyuncu agirlikli: elle incele"
    if oyuncu >= 10 and enbuyuk_pay < 0.3:
        return "yaygin: yanlis alarm adayi"
    return ""


def main():
    arg = sys.argv[1:]
    gun = oyuncu_sec = None
    adet = 15
    kaynaklar = []
    i = 0
    while i < len(arg):
        if arg[i] == "--gun":
            gun = arg[i + 1]; i += 2
        elif arg[i] == "--oyuncu":
            oyuncu_sec = arg[i + 1]; i += 2
        elif arg[i] == "--adet":
            adet = int(arg[i + 1]); i += 2
        else:
            ad, _, yol = arg[i].rpartition("=")
            kaynaklar.append((ad or os.path.basename(os.path.normpath(yol)), yol)); i += 1
    if not kaynaklar:
        print(__doc__)
        return 2

    kayit = [k for k in oku(kaynaklar, gun) if not oyuncu_sec or k["oyuncu"] == oyuncu_sec]
    flag = [k for k in kayit if k["check"] not in ("SETBACK", "CLIENT")]
    istemci = [k for k in kayit if k["check"] == "CLIENT"]
    geri = [k for k in kayit if k["check"] == "SETBACK"]
    gunler = sorted({k["tarih"] for k in kayit})
    print("# HapiAC flag raporu")
    print("kaynak: %s | gun: %s | flag: %d | geri cekme: %d | oyuncu: %d" % (
        ", ".join(e for e, _ in kaynaklar), (gunler[0] + " .. " + gunler[-1]) if gunler else "-",
        len(flag), len(geri), len({k["oyuncu"] for k in kayit})))

    # kaynak ozeti
    if len(kaynaklar) > 1:
        s = []
        for e, _ in kaynaklar:
            f = [k for k in flag if k["kaynak"] == e]
            g = [k for k in geri if k["kaynak"] == e]
            s.append([e, len(f), len(g), len({k["oyuncu"] for k in f + g})])
        tablo("Kaynak ozeti", s, ["kaynak", "flag", "geri cekme", "oyuncu"])

    # check dagilimi
    per = defaultdict(Counter)
    for k in flag:
        per[k["check"]][k["oyuncu"]] += 1
    s = []
    for c, oy in sorted(per.items(), key=lambda kv: -sum(kv[1].values())):
        top = sum(oy.values())
        ad, n = oy.most_common(1)[0]
        s.append([("*" if c in HAPI else "") + c, top, len(oy), "%s %%%d" % (ad, round(100 * n / top)), yorum(top, len(oy), n / top)])
    tablo("Check dagilimi (* = HapiAC check'i)", s, ["check", "flag", "oyuncu", "en cok", "yorum"])

    # hapi check'leri: ornek ayrintilar
    s = []
    for c in sorted(per):
        if c in HAPI:
            ornek = Counter(re.sub(r"\d+(\.\d+)?", "N", k["ayrinti"]) for k in flag if k["check"] == c).most_common(2)
            for o, n in ornek:
                s.append([c, n, o[:90]])
    tablo("HapiAC check'leri: ayrinti kaliplari (sayilar N)", s, ["check", "adet", "kalip"])

    # geri cekmeler
    if geri:
        neden, yer = defaultdict(lambda: [0, set(), 0.0, 0]), Counter()
        for k in geri:
            a = dict(x.split("=", 1) for x in k["ayrinti"].split() if "=" in x)
            n = neden[a.get("neden", "?")]
            n[0] += 1
            n[1].add(k["oyuncu"])
            try:
                n[2] += float(a.get("mesafe", "0"))
            except ValueError:
                pass
            if k["vl"].isdigit() and int(k["vl"]) >= 5:
                n[3] += 1
            yer[(a.get("neden", "?"), a.get("yer", "?"))] += 1
        tablo("Geri cekmeler: nedene gore", [[c, v[0], len(v[1]), "%.2f" % (v[2] / v[0]), v[3]] for c, v in sorted(neden.items(), key=lambda kv: -kv[1][0])],
              ["neden", "adet", "oyuncu", "ort. mesafe", "zincir>=5"])
        tablo("Geri cekmeler: cagri yerine gore", [[c, y, n] for (c, y), n in yer.most_common(adet)], ["neden", "yer", "adet"])
        oy = Counter(k["oyuncu"] for k in geri)
        tablo("Geri cekmeler: oyuncuya gore", [[o, n, ", ".join("%s %d" % cn for cn in Counter(
            dict(x.split("=", 1) for x in k["ayrinti"].split() if "=" in x).get("neden", "?") for k in geri if k["oyuncu"] == o).most_common(3)),
            Counter(k["surum"] + "/" + k["marka"] for k in geri if k["oyuncu"] == o).most_common(1)[0][0]] for o, n in oy.most_common(adet)],
            ["oyuncu", "adet", "nedenler", "surum/marka"])

    # oyuncular
    oy = Counter(k["oyuncu"] for k in flag)
    s = []
    for o, n in oy.most_common(adet):
        benim = [k for k in flag if k["oyuncu"] == o]
        s.append([o, n, ", ".join("%s %d" % cn for cn in Counter(k["check"] for k in benim).most_common(4)),
                  Counter(k["surum"] + "/" + k["marka"] for k in benim).most_common(1)[0][0]])
    tablo("Oyuncular", s, ["oyuncu", "flag", "check'ler", "surum/marka"])

    # surum / marka
    sm = defaultdict(lambda: [0, set()])
    for k in flag:
        v = sm[k["surum"] + " / " + k["marka"]]
        v[0] += 1
        v[1].add(k["oyuncu"])
    tablo("Istemci surumu / markasi", [[a, v[0], len(v[1]), "%.0f" % (v[0] / len(v[1]))] for a, v in sorted(sm.items(), key=lambda kv: -kv[1][0])[:adet]],
          ["surum / marka", "flag", "oyuncu", "flag/oyuncu"])

    # mod kanallari (ad alani = ':' oncesi)
    if istemci:
        alan = defaultdict(set)
        for k in istemci:
            for ad in {c.split(":", 1)[0] for c in k["ayrinti"].split()} or {"(kanal yok)"}:
                alan[ad].add(k["oyuncu"])
        toplam = len({k["oyuncu"] for k in istemci})
        tablo("Mod kanallari (giris yapan %d oyuncu)" % toplam, [[a, len(o), ", ".join(sorted(o)[:4])] for a, o in sorted(alan.items(), key=lambda kv: -len(kv[1]))[:adet * 2]],
              ["ad alani", "oyuncu", "ornek"])
        ms = defaultdict(set)
        for k in istemci:
            ms[k["surum"] + " / " + k["marka"]].add(k["oyuncu"])
        tablo("Giris yapanlarin surumu / markasi", [[a, len(o)] for a, o in sorted(ms.items(), key=lambda kv: -len(kv[1]))[:adet]], ["surum / marka", "oyuncu"])

    # saatlik
    saat = Counter(k["saat"][:2] for k in flag)
    gs = Counter(k["saat"][:2] for k in geri)
    tablo("Saatlik dagilim", [[h, saat[h], gs[h]] for h in sorted(set(saat) | set(gs))], ["saat", "flag", "geri cekme"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
