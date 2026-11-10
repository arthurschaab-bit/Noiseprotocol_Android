"""Mehrere Tagesprotokolle (z. B. aeltere und neue Aufnahmen) zu einer chronologischen Datei zusammenfuehren.

Aufruf:
  python protokoll_zusammenfuehren.py <Ausgabeordner> <Ordner1> [<Ordner2> ...]

Liest aus jedem Ordner tagesprotokoll.md (und die neueste tagesprotokoll*.csv), sortiert die Tage nach Datum und schreibt
<Ausgabeordner>/protokoll_gesamt.md sowie protokoll_gesamt.csv. Ueberschneiden sich Tage, gewinnt der erste Ordner.
"""
import csv
import glob
import os
import re
import sys
from datetime import datetime


def tage_aus_md(pfad):
    text = open(pfad, encoding="utf-8").read()
    kopf, *teile = re.split(r"(?m)^(?=## )", text)
    tage = {}
    for teil in teile:
        m = re.match(r"## \w+, (\d\d)\.(\d\d)\.(\d{4})", teil)
        if m:
            tage[datetime(int(m.group(3)), int(m.group(2)), int(m.group(1))).date()] = teil.rstrip() + "\n"
    return kopf, tage


def main(ausgabe, *ordner):
    os.makedirs(ausgabe, exist_ok=True)
    alle, kopf_text, zeilen = {}, "", {}
    kopf_csv = None
    for o in ordner:
        kopf, tage = tage_aus_md(os.path.join(o, "tagesprotokoll.md"))
        kopf_text = kopf_text or kopf
        for tag, teil in tage.items():
            alle.setdefault(tag, teil)
        csvs = sorted(glob.glob(os.path.join(o, "tagesprotokoll*.csv")), key=os.path.getmtime)
        if csvs:
            with open(csvs[-1], encoding="utf-8-sig", newline="") as f:
                rd = csv.reader(f, delimiter=";")
                k = next(rd)
                kopf_csv = kopf_csv or k
                for z in rd:
                    t = datetime.strptime(z[0], "%Y-%m-%d").date()
                    if t in alle and alle[t].startswith("##"):
                        zeilen.setdefault((t, z[2]), z)
    with open(os.path.join(ausgabe, "protokoll_gesamt.md"), "w", encoding="utf-8") as f:
        f.write(kopf_text.rstrip() + "\n\n" + "\n".join(alle[t] for t in sorted(alle)))
    with open(os.path.join(ausgabe, "protokoll_gesamt.csv"), "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f, delimiter=";")
        if kopf_csv:
            w.writerow(kopf_csv)
        for key in sorted(zeilen):
            w.writerow(zeilen[key])
    # Tabelle im Spaltenformat des Bautagebuchs (Datum;Wochentag;Wahrnehmungszeitraum;Bauphase;Beschreibung;...)
    tb = {}
    kopf_tb = None
    for o in ordner:
        pfad_tb = os.path.join(o, "bautagebuch_vorschlag.csv")
        if os.path.exists(pfad_tb):
            with open(pfad_tb, encoding="utf-8-sig", newline="") as f:
                rd = csv.reader(f, delimiter=";")
                kopf_tb = kopf_tb or next(rd)
                if kopf_tb and rd.line_num == 1:
                    pass
                for z in rd:
                    if z and z[0] != "Datum":
                        tb.setdefault(datetime.strptime(z[0], "%d.%m.%Y").date(), z)
    if kopf_tb:
        with open(os.path.join(ausgabe, "bautagebuch_vorschlag.csv"), "w", encoding="utf-8-sig", newline="") as f:
            w = csv.writer(f, delimiter=";")
            w.writerow(kopf_tb)
            for k in sorted(tb):
                w.writerow(tb[k])
    print(f"{len(alle)} Tage ({min(alle)} bis {max(alle)}), {len(zeilen)} Tag-Klasse-Zeilen")


if __name__ == "__main__":
    main(sys.argv[1], *sys.argv[2:])
