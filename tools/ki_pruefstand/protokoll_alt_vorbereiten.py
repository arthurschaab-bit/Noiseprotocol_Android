"""Eingaben fuer tagesprotokoll.py aus den aelteren Aufnahmen (Juni-August) und den Hoerrunden-Urteilen bauen.

Aufruf:
  python protokoll_alt_vorbereiten.py <alt-Ordner> <gruppen_neu.csv> <urteile.json> <zuordnung.json> <Ausgabeordner>

urteile.json: {"gruppen": {"AL00": "DREHBOHR", ...}, "clips": {"noise_...": "HYD_HAMMER", ...}} (Klassen der Hoerseiten).
Schreibt <Ausgabeordner>/detail.csv (Clips ab 60 dB mit Gruppe), gruppen_urteile.json (Format von tagesprotokoll.py),
pegel_cache/<Tag>.npy (PCE-Sekundenwerte). Annahmen stehen in annahmen.txt.

Regel fuer Gruppen ohne eigenes Gruppenurteil: Haben mindestens 80 % der Einzelurteile (mindestens 4 von 5) dieselbe
Geraeuschart, gilt sie fuer die ganze Gruppe. Sonst gilt die Gruppe als gemischt (das Modell ordnet sie zu).
"""
import csv
import json
import os
import shutil
import sys
from collections import Counter

# Hoerseiten-Klasse (Teil 1-3: Geraet) -> (Geraeuschart, Quelle) wie in gruppen_urteile.json
UEBERSETZUNG = {
    "DREHBOHR": ("MOTOR", "Tiefbohrer (Drehbohrgerät)"),
    "ANKERBOHR": ("BOHREND", "Ankerbohrgerät"),
    "HYD_HAMMER": ("HAEMMERND", "Hydraulikhammer"),
    "DRUCKLUFT": ("HAEMMERND", "Drucklufthammer"),
    "VERLADUNG": ("SCHLAG", "Betonbruch-Verladung"),
    "BAU_SONST": ("BAU_SONST", ""),
    "KEINE_BAU": ("KEINE_BAU", ""),
}
# Geraeuschart-Klassen der neuen Seiten (Teil 4-10) werden unveraendert uebernommen
ARTEN = {"MOTOR", "HAEMMERND", "SCHLAG", "BOHREND", "SAEGEND_SCHLEIFEND", "KLIRREN", "SIGNAL", "SPRACHE_MUSIK",
         "VERKEHR_EXTERN", "SIRENE", "WIND_RAUSCHEN", "STILLE", "MESSGERAET"}


def uebersetze(klasse, quelle=""):
    if klasse in UEBERSETZUNG:
        return UEBERSETZUNG[klasse]
    if klasse in ARTEN:
        return klasse, quelle
    return None  # UNKLAR, GEMISCHT, leer


def baustelle(art):
    return "nein" if art in ("KEINE_BAU", "SPRACHE_MUSIK", "VERKEHR_EXTERN", "SIRENE", "WIND_RAUSCHEN", "STILLE", "MESSGERAET") else "ja"


def main(alt, gruppen_csv, urteile_pfad, zuordnung_pfad, ausgabe):
    os.makedirs(ausgabe, exist_ok=True)
    urteile = json.load(open(urteile_pfad, encoding="utf-8"))
    zuordnung = json.load(open(zuordnung_pfad, encoding="utf-8"))
    G, C = urteile["gruppen"], urteile["clips"]
    annahmen = []

    gruppen_aus = {}
    for gid, clips in zuordnung.items():
        if gid in G and uebersetze(G[gid]):
            art, quelle = uebersetze(G[gid])
            gruppen_aus[gid] = {"art": art, "quelle": quelle, "baustelle": baustelle(art)}
            continue
        einzel = [uebersetze(C[c]) for c in clips if c in C and uebersetze(C[c])]
        if len(einzel) >= 4:
            arten = Counter(e[0] for e in einzel)
            art, n = arten.most_common(1)[0]
            if n / len(einzel) >= 0.8:
                quellen = Counter(e[1] for e in einzel if e[0] == art and e[1])
                quelle = " / ".join(sorted(quellen)) if quellen else ""
                gruppen_aus[gid] = {"art": art, "quelle": quelle, "baustelle": baustelle(art)}
                annahmen.append(f"Gruppe {gid}: kein Gruppenurteil, {n} von {len(einzel)} Einzelurteilen = {art}; fuer die ganze Gruppe uebernommen")
                continue
        if gid in G or any(c in C for c in clips):
            gruppen_aus[gid] = {"art": "GEMISCHT", "quelle": "", "baustelle": "ja"}
            annahmen.append(f"Gruppe {gid}: gemischt, nur Einzelurteile; das Modell ordnet die uebrigen Clips zu")
    clips_aus = {}
    for c, k in C.items():
        u = uebersetze(k)
        if u:
            clips_aus[c] = {"art": u[0], "quelle": u[1], "baustelle": baustelle(u[0])}
    json.dump({"gruppen": gruppen_aus, "clips": clips_aus}, open(os.path.join(ausgabe, "gruppen_urteile.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    # Cluster der Clips ab 75 dB (AL), alle anderen -1
    cluster = {}
    with open(gruppen_csv, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f, delimiter=";"):
            if r["menge"] == "AL":
                cluster[r["clip"]] = r["cluster"]
    n = 0
    with open(os.path.join(alt, "ereignisse.csv"), encoding="utf-8", newline="") as f, \
            open(os.path.join(ausgabe, "detail.csv"), "w", encoding="utf-8-sig", newline="") as g:
        w = csv.writer(g, delimiter=";")
        w.writerow(["clip", "zeit", "tag", "pegel_db", "cluster"])
        for r in csv.DictReader(f, delimiter=";"):
            clip = r["wav"][:-4]
            w.writerow([clip, r["zeit"], r["zeit"][:10], str(r["pegel"]).replace(".", ","), cluster.get(clip, "-1")])
            n += 1
    os.makedirs(os.path.join(ausgabe, "pegel_cache"), exist_ok=True)
    for p in os.listdir(os.path.join(alt, "pegel")):
        shutil.copy2(os.path.join(alt, "pegel", p), os.path.join(ausgabe, "pegel_cache", p))
    with open(os.path.join(ausgabe, "annahmen.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(annahmen))
    print(f"{n} Clips, {len(gruppen_aus)} Gruppen mit Urteil, {len(clips_aus)} Einzelurteile; Annahmen:")
    print("\n".join(annahmen))


if __name__ == "__main__":
    main(*sys.argv[1:6])
