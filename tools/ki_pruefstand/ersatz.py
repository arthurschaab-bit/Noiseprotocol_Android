"""Ersetzt Kandidaten, deren WAV nicht in den Zips liegt, durch den naechsten passenden."""
import csv, glob, os, random, sys, zipfile
from collections import Counter
from auswahl import lade, arbeitszeit

wurzel, kand, fehlend = sys.argv[1], sys.argv[2], set(sys.argv[3:])
rows = list(csv.DictReader(open(kand, encoding="utf-8"), delimiter=";"))
belegt = {(r["zeit"][:10], r["zeit"][11:13]) for r in rows}
tage = Counter(r["zeit"][:10] for r in rows if r["rolle"] == "laut")
alle = {z["wav"]: z for z in lade(wurzel)}

def vorhanden(z):
    tag = f"{z['zeit']:%Y-%m-%d}"
    for zp in glob.glob(os.path.join(glob.escape(wurzel), tag, "WAV", glob.escape(z["zip"][:-4]) + "*.zip")):
        if any(os.path.basename(n) == z["wav"] for n in zipfile.ZipFile(zp).namelist()):
            return True
    return False

rnd = random.Random(2)
for i, r in enumerate(rows):
    if r["wav"] not in fehlend:
        continue
    alt = alle[r["wav"]]
    if r["rolle"] == "laut":
        pool = sorted((z for z in alle.values() if arbeitszeit(z) and z["pegel"] < alt["pegel"]), key=lambda z: -z["pegel"])
    else:
        pool = [z for z in alle.values() if arbeitszeit(z) and z["pegel"] < 70]; rnd.shuffle(pool)
    for z in pool:
        slot = (f"{z['zeit']:%Y-%m-%d}", f"{z['zeit']:%H}")
        if slot in belegt or (r["rolle"] == "laut" and tage[slot[0]] >= 3) or not vorhanden(z):
            continue
        belegt.add(slot)
        rows[i] = dict(r, zeit=z["zeit"].isoformat(), pegel_db=z["pegel"], label=z["label"], zip=z["zip"], wav=z["wav"])
        print("ersetzt", r["wav"], "->", z["wav"], z["pegel"]); break
with open(kand, "w", encoding="utf-8", newline="") as f:
    w = csv.DictWriter(f, fieldnames=rows[0].keys(), delimiter=";"); w.writeheader(); w.writerows(rows)
