"""Schritt A: Kandidaten aus den Nachtrag-CSVs waehlen (ohne Audio, nur lesend).

Aufruf: python auswahl.py <Drive-Wurzel> <Ausgabe.csv> [--seed 1]
Die Ausgabe gehoert NICHT ins Repo (enthaelt Aufnahmenamen).
"""
import argparse, csv, glob, io, os, random, re, sys, time
from collections import defaultdict
from datetime import datetime

ARBEIT = (7, 18)  # Annahme: Mo-Fr 07:00-18:00, mit Owner abzustimmen


def lade(wurzel, wartezeiten=(2, 4, 6, 8, 10)):
    zeilen = []
    pat = os.path.join(glob.escape(wurzel), "*", "Schallmessung", "klassifikation_nachtrag_*.csv")
    for p in sorted(glob.glob(pat)):
        text = None
        for versuch in range(len(wartezeiten) + 1):  # Drive liefert sporadisch Lesefehler (OSError 22)
            try:
                with open(p, encoding="utf-8-sig", newline="") as f:
                    text = f.read()
                break
            except OSError:
                if versuch < len(wartezeiten):
                    print(f"Lesefehler bei {p}, neuer Versuch in {wartezeiten[versuch]} s", file=sys.stderr, flush=True)
                    time.sleep(wartezeiten[versuch])
        if text is None:
            print(f"WARNUNG: {p} nicht lesbar, uebersprungen", file=sys.stderr)
            continue
        if True:
            for r in csv.DictReader(io.StringIO(text, newline=""), delimiter=";"):
                try:
                    t = datetime.fromisoformat(r["Zeit"])
                    pegel = float(r["Pegel_dB"].replace(",", "."))
                except (ValueError, KeyError):
                    continue
                m = re.match(r"(\d{8})_(\d\d)_", r["Aufnahme"])
                zeilen.append(dict(zeit=t, wav=r["Aufnahme"], pegel=pegel,
                                   label=r["Klassifikation"],
                                   zip=f"audio_{t:%Y-%m-%d}_{t:%H}-00.zip"))
    return zeilen


def arbeitszeit(z):
    t = z["zeit"]
    return t.weekday() < 5 and ARBEIT[0] <= t.hour < ARBEIT[1]


def waehle(zeilen, n_laut=25, n_gegen=12, seed=1):
    rnd = random.Random(seed)
    arbeit = sorted((z for z in zeilen if arbeitszeit(z)), key=lambda z: -z["pegel"])
    # je (Tag, Stunde) hoechstens 1 Clip, je Tag hoechstens 3 -> Streuung
    laut, slots, tage = [], set(), defaultdict(int)
    for z in arbeit:
        slot = (z["zeit"].date(), z["zeit"].hour)
        if slot in slots or tage[z["zeit"].date()] >= 3:
            continue
        slots.add(slot); tage[z["zeit"].date()] += 1
        laut.append(dict(z, rolle="laut"))
        if len(laut) == n_laut:
            break
    # Frueh (Mo-Fr 06-07): normale Kandidaten laut Owner, lauteste je Tag
    # Negativ-Kandidaten: Nacht (22-06) oder Wochenende, lauteste je Tag, 6 Tage
    def lauteste_je_tag(liste, n, rolle):
        out, belegt = [], set()
        for z in sorted(liste, key=lambda z: -z["pegel"]):
            d = z["zeit"].date()
            if d not in belegt:
                belegt.add(d); out.append(dict(z, rolle=rolle))
            if len(out) == n:
                break
        return out
    frueh = lauteste_je_tag([z for z in zeilen if z["zeit"].weekday() < 5 and z["zeit"].hour == 6], 6, "laut_frueh")
    nacht_we = [z for z in zeilen if z["zeit"].weekday() >= 5 or z["zeit"].hour >= 22 or z["zeit"].hour < 6]
    gegen = lauteste_je_tag(nacht_we, 6, "negativ_nacht_we")
    leise = [z for z in arbeit if z["pegel"] < 70]
    gegen += [dict(z, rolle="gegen_leise") for z in rnd.sample(leise, min(6, len(leise)))]
    laut = laut + frueh
    return laut + gegen


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("wurzel"); ap.add_argument("ausgabe"); ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    z = lade(a.wurzel)
    print(f"{len(z)} Ereignisse aus {len({x['zeit'].date() for x in z})} Tagen")
    sel = waehle(z, seed=a.seed)
    with open(a.ausgabe, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["rolle", "zeit", "pegel_db", "label", "zip", "wav"])
        for s in sel:
            w.writerow([s["rolle"], s["zeit"].isoformat(), s["pegel"], s["label"], s["zip"], s["wav"]])
    for s in sel:
        print(f"{s['rolle']:17} {s['zeit']:%Y-%m-%d %a %H:%M:%S} {s['pegel']:5.1f} dB  {s['label']:40} {s['zip']}")
