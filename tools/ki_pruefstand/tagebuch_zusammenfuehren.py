"""Bautagebuch (xlsx) des Owners mit dem automatisch erzeugten Tagebuch-Vorschlag zu einer Arbeitsmappe zusammenfuehren.

Aufruf:
  python tagebuch_zusammenfuehren.py <Bautagebuch_Vx.xlsx> <bautagebuch_vorschlag.csv> <Berichtordner> <widersprueche.json> <Ausgabe.xlsx>
         [--version 25] [--stand 10.11.2026] [--phase "Phase 3: Tiefbau"]

Die Quelldatei wird nur gelesen (die Ausgabe ist eine neue Datei). Was passiert:
  1. Platzhalter ("[bitte ergaenzen]" u. a.) werden aus den Eintraegen entfernt.
  2. Tage ohne Eintrag werden aus dem Vorschlag ergaenzt (Kennzeichnung "nachtraeglicher Eintrag", Bauphase = --phase angenommen,
     Einschraenkung aus dem Gesamtbericht: "+X dB ueber AVV-Richtwert"). Tage mit Eintrag behalten ihren Text; nur Neues aus den
     Aufnahmen wird als "Aus den Aufnahmen zusaetzlich" angehaengt, ausser der Tag steht in der Widerspruchsliste als offen.
  3. Referenztage aus dem Gesamtbericht (Bauruhe-Messungen) werden als Zeile ergaenzt.
  4. Neue Blaetter: "Abgleich Gesamtbericht" (Widersprueche) und "Berichtstage ohne Eintrag".
  5. Kopfzeile, Filter und Fixierung werden angepasst; ein Aenderungshinweis kommt in die Legende.
Bericht-JSONs: erzeugt von bericht_lesen.py.
"""
import argparse
import copy
import csv
import json
import math
import os
import re
import sys
from datetime import datetime

import openpyxl
from openpyxl.styles import Alignment, Font, PatternFill

PLATZHALTER = [
    r"\s*Einschränkungen: \[bitte ergänzen\]\.?",
    r"\s*\[Betroffene Räume/Uhrzeiten bitte ergänzen\.\]",
    r"\s*\[weitere betroffene Räume / Dauer bitte ergänzen\.\]",
    r"\s*\[[^\]]*bitte ergänzen[^\]]*\]\.?",
]
DATUM = re.compile(r"^\d\d\.\d\d\.\d{4}$")
WOCHENTAG = ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"]
BREITEN = {5: 55, 6: 50, 7: 60}


def d(s):
    return datetime.strptime(s, "%d.%m.%Y")


def bereinige(text):
    if not text:
        return text, 0
    n = 0
    for p in PLATZHALTER:
        text, k = re.subn(p, "", text)
        n += k
    text = re.sub(r"[ \t]{2,}", " ", text)
    text = re.sub(r"\s+([.,;])", r"\1", text).strip()
    return text, n


def datenmenge(feld):
    """'29.06.2026 bis 03.07.2026' / '01.06.2026, 09.06.2026' -> Menge von Datums-Strings."""
    tage = set()
    for m in re.finditer(r"(\d\d\.\d\d\.\d{4}) bis (\d\d\.\d\d\.\d{4})", feld):
        a, b = d(m.group(1)), d(m.group(2))
        tage |= {x.strftime("%d.%m.%Y") for x in (a.fromordinal(o) for o in range(a.toordinal(), b.toordinal() + 1))}
    tage |= set(re.findall(r"\d\d\.\d\d\.\d{4}", feld))
    return tage


def hoehe(werte):
    zeilen = 1
    for spalte, breite in BREITEN.items():
        t = werte[spalte - 1] or ""
        zeilen = max(zeilen, sum(max(1, math.ceil(len(z) / (breite * 1.05))) for z in str(t).split("\n")))
    return max(24, 13.2 * zeilen + 4)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("quelle")
    ap.add_argument("vorschlag")
    ap.add_argument("bericht")
    ap.add_argument("widersprueche")
    ap.add_argument("ausgabe")
    ap.add_argument("--version", default="25")
    ap.add_argument("--stand", default=datetime.now().strftime("%d.%m.%Y"))
    ap.add_argument("--phase", default="Phase 3: Tiefbau")
    a = ap.parse_args()

    bt = json.load(open(os.path.join(a.bericht, "bericht_tage.json"), encoding="utf-8"))
    bs = json.load(open(os.path.join(a.bericht, "bericht_tagesseiten.json"), encoding="utf-8"))
    wid = json.load(open(a.widersprueche, encoding="utf-8"))
    kein_zusatz = set()
    for w in wid:
        if w["typ"] == "Offen" or "Aufnahmen" in w["bericht"]:
            kein_zusatz |= datenmenge(w["datum"])

    wb = openpyxl.load_workbook(a.quelle)
    ws = wb.worksheets[0]
    # --- vorhandene Zeilen sichern ---------------------------------------------------------------
    orig = []
    for r in range(6, ws.max_row + 1):
        v = [ws.cell(r, c).value for c in range(1, 8)]
        if v[0] and DATUM.match(str(v[0])):
            orig.append(dict(werte=[None if x is None else str(x) for x in v], stil=[copy.copy(ws.cell(r, c)._style) for c in range(1, 8)],
                             hoehe=ws.row_dimensions[r].height, fill=ws.cell(r, 1).fill.fgColor.rgb))
    vorlage = next(x for x in orig if x["werte"][3].startswith("Phase 3") and x["fill"] == "FFEBF3FB")
    gruen = next((x for x in orig if x["werte"][0] == "30.05.2026"), vorlage)
    entfernt = 0
    for x in orig:
        for i in (2, 4, 5, 6):
            x["werte"][i], k = bereinige(x["werte"][i])
            entfernt += k
        if x["fill"] in ("00000000", None) and x["werte"][3].startswith("Phase 3"):
            x["stil"], x["hoehe"] = vorlage["stil"], None  # uneinheitlich formatierte Zeilen angleichen
    vorhanden = {x["werte"][0]: x for x in orig}

    # --- Vorschlag einlesen ---------------------------------------------------------------------------
    vorschlag = {}
    with open(a.vorschlag, encoding="utf-8-sig", newline="") as f:
        for z in csv.DictReader(f, delimiter=";"):
            vorschlag[z["Datum"]] = z
    ergaenzt = zusaetze = 0
    neu = []
    for datum, z in vorschlag.items():
        text = z["Beschreibung / Geräusche"]
        if datum in vorhanden:
            if datum in kein_zusatz:
                continue
            # Abkuerzungen schuetzen, "Messwerte s. Schallbericht ..." entfernen, dann in Saetze teilen
            roh = re.sub(r"\s*Messwerte s\. Schallbericht \d\d\.\d\d\.?", "", text).replace("z. B.", "z.§B.")
            satz = [s.strip().replace("§", " ") for s in re.split(r"(?<=\.)\s+(?=[A-ZÄÖÜ])", roh) if s.strip()]
            alt = vorhanden[datum]["werte"][4] or ""
            # nur Saetze zu Quellen, die im vorhandenen Eintrag noch nicht vorkommen
            stamm = [("Tiefbohrer", r"Tiefbohr"), ("Baggergeräusch", r"Bagger"), ("Hämmern", r"Hämmer|hammer"),
                     ("Rückfahrwarner", r"Rückfahrwarn|Warnton|Piep"), ("Schläge", r"Schläge|Schlag"), ("Säge", r"Säge|Schleif")]
            behalten = []
            for s in satz:
                quellen = [rx for wort, rx in stamm if wort in s]
                if not quellen or not any(re.search(rx, alt, re.I) for rx in quellen):
                    behalten.append(s)
            satz = [re.sub(r"^(Zusätzlich|Außerdem) ", "", s) for s in behalten]
            if satz:
                vorhanden[datum]["werte"][4] = (alt + " Aus den Aufnahmen zusätzlich: " + " ".join(satz)).strip()
                zusaetze += 1
            continue
        t = bt.get(datum)
        ein = bs.get(datum, [{}])[0].get("einordnung", "")
        m = re.match(r"\+(\d+,\d) dB", ein)
        teile = []
        if m:
            teile.append(f"+{m.group(1)} dB über AVV-Richtwert (55 dB(A)).")
        if t and t["stufe"] in ("(M)", "(U)"):
            teile.append(f"Teilerfassung (Abdeckung {t['abd']}), kein Tageswert.")
        teile.append("[Nachträglicher Eintrag auf Basis der Auswertung der Tonaufnahmen]")
        neu.append(dict(werte=[datum, z["Wochentag"], z["Wahrnehmungszeitraum"], a.phase, text, " ".join(teile), "WAV-Aufnahmen vorhanden."],
                        stil=vorlage["stil"], hoehe=None, fill=vorlage["fill"]))
        ergaenzt += 1
    # Referenztage (Bauruhe-Messungen) aus dem Bericht
    referenz = 0
    for tag, ort in (("27.06.2026", "SO-Balkon"), ("28.06.2026", "NW-Balkon")):
        if tag in vorhanden or tag not in bt:
            continue
        t = bt[tag]
        neu.append(dict(werte=[tag, WOCHENTAG[d(tag).weekday()], "-", a.phase,
                               f"Bauruhe-Referenzmessung (Wochenende), {ort}: LAeq 07–20 Uhr {t['laeq']} dB(A), Abdeckung {t['abd']}. Messwerte s. Schallbericht {tag[:5]}.",
                               "Referenz Bauruhe (Basislinie). [Nachträglicher Eintrag auf Basis des Gesamtberichts]", ""],
                        stil=gruen["stil"], hoehe=None, fill=gruen["fill"]))
        referenz += 1

    # --- Zeilen neu schreiben ---------------------------------------------------------------------------
    alle = sorted(orig + neu, key=lambda x: d(x["werte"][0]))
    ws.delete_rows(6, ws.max_row)
    for i, x in enumerate(alle):
        r = 6 + i
        for c in range(1, 8):
            zelle = ws.cell(r, c, x["werte"][c - 1])
            zelle._style = copy.copy(x["stil"][c - 1])
        ws.row_dimensions[r].height = hoehe(x["werte"]) if (x["hoehe"] is None or x in neu or x["werte"][4] != "") else x["hoehe"]
    letzte = 5 + len(alle)
    ws.auto_filter.ref = f"A5:G{letzte}"
    ws.freeze_panes = "A6"
    kopf = str(ws["A2"].value)
    ws["A2"].value = re.sub(r"– \d\d\.\d\d\.\d{4} – Version \d+ \(Stand [^)]*\)",
                            f"– {alle[-1]['werte'][0]} – Version {a.version} (Stand {a.stand})", kopf)

    # --- Legende: Aenderungshinweis ------------------------------------------------------------------------
    lg = wb.worksheets[1]
    start = lg.max_row + 2
    zeilen = [
        (f"Änderungshinweis V{a.version} ({a.stand})", ""),
        ("Zusammenführung", f"{ergaenzt} Tage aus der Auswertung der Tonaufnahmen ergänzt (gekennzeichnet als nachträgliche Einträge); {referenz} Referenztage (Bauruhe) aus dem Gesamtbericht ergänzt; bei {zusaetze} vorhandenen Einträgen steht ergänzend 'Aus den Aufnahmen zusätzlich'."),
        ("Platzhalter", f"{entfernt} Platzhalter ('[bitte ergänzen]') entfernt. Fehlende Angaben zu betroffenen Räumen, Uhrzeiten und Einschränkungen können jederzeit ergänzt werden."),
        ("Ergänzte Einträge", f"Bauphase für ergänzte Tage nach dem 03.07.2026 angenommen ({a.phase}); Einträge beschreiben das Lärmgeschehen laut Tonaufnahmen und Pegelverlauf, nicht die persönliche Wahrnehmung. Zeiten gerundet (ca.)."),
        ("Abgleich", f"Blatt 'Abgleich Gesamtbericht': {len(wid)} Punkte, in denen Tagebuch und Gesamtbericht nicht übereinstimmen oder sich nicht ergänzen. Blatt 'Berichtstage ohne Eintrag': Messtage des Berichts ohne Tagebucheintrag."),
        ("Formatierung", "Filter und Fixierung der Kopfzeile korrigiert, uneinheitlich formatierte Zeilen ab Mai 2026 angeglichen."),
    ]
    for i, (k, v) in enumerate(zeilen):
        lg.cell(start + i, 1, k).font = Font(bold=True)
        lg.cell(start + i, 2, v).alignment = Alignment(wrap_text=True, vertical="top")

    # --- neue Blaetter ----------------------------------------------------------------------------------------
    kopf_stil = dict(font=Font(bold=True, name="Calibri", size=10), fill=PatternFill("solid", fgColor="FFD9D9D9"))
    farbe = {"Widerspruch": "FFFCE4D6", "Intern": "FFFCE4D6", "Hinweis": "FFFFF2CC", "Lücke im Tagebuch": "FFE2EFDA",
             "Lücke im Bericht": "FFE2EFDA", "Offen": "FFDDEBF7"}
    ab = wb.create_sheet("Abgleich Gesamtbericht")
    ab.append(["Nr", "Datum", "Art", "Thema", "Aussage im Tagebuch", "Aussage im Gesamtbericht", "Vorschlag"])
    for w in wid:
        ab.append([w["nr"], w["datum"], w["typ"], w["thema"], w["tagebuch"], w["bericht"], w["vorschlag"]])
    for c, b in zip("ABCDEFG", (5, 22, 16, 28, 55, 60, 55)):
        ab.column_dimensions[c].width = b
    for zelle in ab[1]:
        zelle.font, zelle.fill = kopf_stil["font"], kopf_stil["fill"]
    for r in range(2, ab.max_row + 1):
        for c in range(1, 8):
            ab.cell(r, c).alignment = Alignment(wrap_text=True, vertical="top")
            ab.cell(r, c).font = Font(name="Calibri", size=10)
        ab.cell(r, 3).fill = PatternFill("solid", fgColor=farbe.get(ab.cell(r, 3).value, "FFFFFFFF"))
    ab.freeze_panes = "A2"
    ab.auto_filter.ref = f"A1:G{ab.max_row}"

    im_tagebuch = {x["werte"][0] for x in alle}
    bo = wb.create_sheet("Berichtstage ohne Eintrag")
    bo.append(["Datum", "Wt", "Stufe", "LAeq dB(A)", "Maximum dB(A)", "Phasen ≥60 dB/10 min", "Abdeckung", "Messort", "Einordnung"])
    for dat in sorted(bt, key=d):
        if dat in im_tagebuch:
            continue
        t, s = bt[dat], bs.get(dat, [{}])[0]
        bo.append([dat, t["wt"], t["stufe"], t["laeq"], t["max"], t["ph"], t["abd"], s.get("ort", ""), s.get("einordnung", "")])
    for c, b in zip("ABCDEFGHI", (13, 5, 7, 11, 13, 14, 11, 40, 32)):
        bo.column_dimensions[c].width = b
    for zelle in bo[1]:
        zelle.font, zelle.fill = kopf_stil["font"], kopf_stil["fill"]
    bo.freeze_panes = "A2"

    wb.save(a.ausgabe)
    print(f"{len(alle)} Eintraege ({len(orig)} vorhanden, {ergaenzt} ergaenzt, {referenz} Referenztage, {zusaetze} mit Zusatz), "
          f"{entfernt} Platzhalter entfernt, {bo.max_row - 1} Berichtstage ohne Eintrag -> {a.ausgabe}")


if __name__ == "__main__":
    sys.exit(main())
