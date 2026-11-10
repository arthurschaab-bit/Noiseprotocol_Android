"""Eingaben fuer tagesprotokoll.py aus den Hoerrunden-Urteilen bauen (neue Aufnahmen und aeltere Aufnahmen).

Aufruf:
  python protokoll_vorbereiten.py neu|alt <Ergebnisordner> <gruppen_neu.csv> <urteile_hoerrunde2.json> <zuordnung2.json> <Ausgabeordner>
         [--urteile-runde1 gruppen_urteile.json --zuordnung-runde1 seiten/zuordnung.json]

neu: <Ergebnisordner> = voll (detail.csv, shards); Clips ab 75 dB tragen die Gruppen der Runde 1 (Zahlen), leisere die Gruppen NQxx.
alt: <Ergebnisordner> = alt (ereignisse.csv, shards, pegel); Gruppen ALxx und AQxx.
urteile_hoerrunde2.json: {"gruppen": {"AL00": {"klasse": ..., "quelle": ..., "baustelle": ..., "notiz": ...}}, "clips": {"noise_...": {"klasse": ...}}}
Schreibt <Ausgabeordner>/detail.csv, gruppen_urteile.json (Format von tagesprotokoll.py), annahmen.txt.

Regeln:
  - Hoerseiten-Klassen der Teile 1-3 (Geraet, z. B. DREHBOHR) werden in (Geraeuschart, Quelle) uebersetzt.
  - Nur Quelle ohne Art (z. B. Bagger, Tiefbohrer): Art MOTOR, die Quelle bestimmt die Klasse.
  - Art GEMISCHT mit bekannter Quelle (Tiefbohrer, Bagger): die Quelle bestimmt die Klasse.
  - Baustelle = nein ohne Art: Umgebung (KEINE_BAU).
  - Ohne Gruppenurteil: stimmen mindestens 80 % der Einzelurteile ueberein, gilt die Art fuer die Gruppe, sonst ist sie gemischt
    (das Modell ordnet zu). Gruppen ganz ohne Urteil sind OFFEN und werden nicht klassifiziert.
"""
import argparse
import csv
import json
import os
import shutil
from collections import Counter

UEBERSETZUNG = {
    "DREHBOHR": ("MOTOR", "Tiefbohrer (Drehbohrgerät)"),
    "ANKERBOHR": ("MOTOR", "Tiefbohrer (Drehbohrgerät)"),  # Owner: kein eigenes Ankerbohrgeraet
    "HYD_HAMMER": ("HAEMMERND", "Hydraulikhammer"),
    "DRUCKLUFT": ("HAEMMERND", "Drucklufthammer"),
    "VERLADUNG": ("SCHLAG", "Betonbruch-Verladung"),
    "BAU_SONST": ("BAU_SONST", ""),
    "KEINE_BAU": ("KEINE_BAU", ""),
}
ARTEN = {"MOTOR", "HAEMMERND", "SCHLAG", "BOHREND", "SAEGEND_SCHLEIFEND", "KLIRREN", "SIGNAL", "SPRACHE_MUSIK",
         "VERKEHR_EXTERN", "SIRENE", "WIND_RAUSCHEN", "STILLE", "MESSGERAET"}
KEINE = {"KEINE_BAU", "SPRACHE_MUSIK", "VERKEHR_EXTERN", "SIRENE", "WIND_RAUSCHEN", "STILLE", "MESSGERAET"}


def baustelle(art):
    return "nein" if art in KEINE else "ja"


def normiere(v):
    """Urteil -> {'art','quelle','baustelle'} | 'GEMISCHT' | None (kein Urteil / unklar)."""
    if v is None:
        return None
    if isinstance(v, str):
        v = {"klasse": v}
    k, q, b = v.get("klasse") or "", (v.get("quelle") or "").strip(), v.get("baustelle") or ""
    if k in UEBERSETZUNG:
        art, quelle = UEBERSETZUNG[k]
        return {"art": art, "quelle": q or quelle, "baustelle": baustelle(art)}
    if k in ARTEN:
        return {"art": k, "quelle": q, "baustelle": b or baustelle(k)}
    if k == "UNKLAR":
        return None
    if k == "GEMISCHT":
        return {"art": "MOTOR", "quelle": q, "baustelle": b or "ja"} if q else "GEMISCHT"
    if q:  # nur eine Quelle gewaehlt
        return {"art": "MOTOR", "quelle": q, "baustelle": b or "ja"}
    if b == "nein":
        return {"art": "KEINE_BAU", "quelle": "", "baustelle": "nein"}
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("quelle", choices=["neu", "alt"])
    ap.add_argument("ergebnis")
    ap.add_argument("gruppen_csv")
    ap.add_argument("urteile")
    ap.add_argument("zuordnung")
    ap.add_argument("ausgabe")
    ap.add_argument("--urteile-runde1", default="")
    ap.add_argument("--zuordnung-runde1", default="")
    a = ap.parse_args()
    os.makedirs(a.ausgabe, exist_ok=True)

    u = json.load(open(a.urteile, encoding="utf-8"))
    zuordnung = json.load(open(a.zuordnung, encoding="utf-8"))
    rohG, rohC = u["gruppen"], u["clips"]
    praefix = ("NQ",) if a.quelle == "neu" else ("AL", "AQ")
    annahmen, G, C = [], {}, {}

    for gid, clips in zuordnung.items():
        if not gid.startswith(praefix):
            continue
        n = normiere(rohG.get(gid))
        if isinstance(n, dict):
            G[gid] = n
            continue
        einzel = [normiere(rohC.get(c)) for c in clips if c in rohC]
        einzel = [e for e in einzel if isinstance(e, dict)]
        if len(einzel) >= 4:
            arten = Counter(e["art"] for e in einzel)
            art, k = arten.most_common(1)[0]
            if k / len(einzel) >= 0.8:
                quellen = Counter(e["quelle"] for e in einzel if e["art"] == art and e["quelle"])
                G[gid] = {"art": art, "quelle": " / ".join(sorted(quellen)), "baustelle": baustelle(art)}
                annahmen.append(f"Gruppe {gid}: {k} von {len(einzel)} Einzelurteilen = {art}, für die ganze Gruppe übernommen")
                continue
        if n == "GEMISCHT" or einzel or gid in rohG or any(c in rohC for c in clips):
            G[gid] = {"art": "GEMISCHT", "quelle": "", "baustelle": "ja"}
            annahmen.append(f"Gruppe {gid}: gemischt, das Modell ordnet die Clips zu")
        else:
            G[gid] = {"art": "OFFEN", "quelle": "", "baustelle": ""}
            annahmen.append(f"Gruppe {gid}: kein Urteil, wird nicht klassifiziert")
    for c, v in rohC.items():
        n = normiere(v)
        if isinstance(n, dict):
            C[c] = n

    # Gruppen der Runde 1 (nur neue Aufnahmen ab 75 dB)
    gehoert = {c for cs in zuordnung.values() for c in cs}
    if a.quelle == "neu" and a.urteile_runde1:
        r1 = json.load(open(a.urteile_runde1, encoding="utf-8"))
        G.update(r1["gruppen"])
        C.update(r1["clips"])
        z1 = json.load(open(a.zuordnung_runde1, encoding="utf-8"))
        zuordnung.update(z1)

    cluster = {}
    with open(a.gruppen_csv, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f, delimiter=";"):
            if r["cluster"].startswith(praefix):
                cluster[r["clip"]] = r["cluster"]

    n = 0
    with open(os.path.join(a.ausgabe, "detail.csv"), "w", encoding="utf-8-sig", newline="") as g:
        w = csv.writer(g, delimiter=";")
        w.writerow(["clip", "zeit", "tag", "pegel_db", "cluster"])
        if a.quelle == "neu":
            with open(os.path.join(a.ergebnis, "detail.csv"), encoding="utf-8-sig", newline="") as f:
                for r in csv.DictReader(f, delimiter=";"):
                    c = r["cluster"]
                    if c == "-1":
                        c = cluster.get(r["clip"], "-1")
                    w.writerow([r["clip"], r["zeit"], r["tag"], r["pegel_db"], c])
                    n += 1
        else:
            with open(os.path.join(a.ergebnis, "ereignisse.csv"), encoding="utf-8", newline="") as f:
                for r in csv.DictReader(f, delimiter=";"):
                    clip = r["wav"][:-4]
                    w.writerow([clip, r["zeit"], r["zeit"][:10], str(r["pegel"]).replace(".", ","), cluster.get(clip, "-1")])
                    n += 1
            os.makedirs(os.path.join(a.ausgabe, "pegel_cache"), exist_ok=True)
            for p in os.listdir(os.path.join(a.ergebnis, "pegel")):
                shutil.copy2(os.path.join(a.ergebnis, "pegel", p), os.path.join(a.ausgabe, "pegel_cache", p))
    json.dump({"gruppen": G, "clips": C}, open(os.path.join(a.ausgabe, "gruppen_urteile.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(zuordnung, open(os.path.join(a.ausgabe, "zuordnung.json"), "w", encoding="utf-8"))
    with open(os.path.join(a.ausgabe, "annahmen.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(annahmen))
    art_n = Counter(g["art"] for g in G.values())
    print(f"{n} Clips; {len(G)} Gruppen ({dict(art_n)}); {len(C)} Einzelurteile")
    print("\n".join(annahmen))


if __name__ == "__main__":
    main()
