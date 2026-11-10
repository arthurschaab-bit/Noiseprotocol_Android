"""Gesamtbericht (Schallmessung, PDF) einlesen: Tageswerte und Tagesseiten als JSON.

Aufruf:
  python bericht_lesen.py <Gesamtbericht.pdf> <Ausgabeordner>

Schreibt <Ausgabeordner>/gesamtbericht.txt (Text je Seite), bericht_tage.json (Tagesuebersicht: LAeq, Maximum, L1, Phasen >= 60 dB/10 min,
Abdeckung, Geraet) und bericht_tagesseiten.json (je Tag: Messort, Einordnung "+X dB ueber Richtwert", LAeq-Text, lauteste Stunde,
Videobelege, Wetterereignisse). Nur lesend; das PDF wird nicht veraendert. Benoetigt PyMuPDF.
"""
import json
import os
import re
import sys

import fitz  # PyMuPDF


def main(pdf, ausgabe):
    os.makedirs(ausgabe, exist_ok=True)
    d = fitz.open(pdf)
    seiten = {i + 1: p.get_text() for i, p in enumerate(d)}
    with open(os.path.join(ausgabe, "gesamtbericht.txt"), "w", encoding="utf-8") as f:
        f.write("".join(f"\n=====SEITE {n}=====\n{t}" for n, t in seiten.items()))

    # Tagesuebersicht: je Tag 9 Zellen (Datum, Wt, Stufe, LAeq, Maximum, L1, Ph, Abdeckung, Geraet)
    tage = {}
    for t in seiten.values():
        if "Tagesübersicht" not in t:
            continue
        toks = [x.strip() for x in t.splitlines() if x.strip()]
        i = 0
        while i < len(toks):
            if re.fullmatch(r"\d\d\.\d\d\.\d{4}", toks[i]) and i + 8 < len(toks):
                z = toks[i:i + 9]
                tage[z[0]] = dict(wt=z[1], stufe=z[2], laeq=z[3], max=z[4], l1=z[5], ph=z[6], abd=z[7], geraet=z[8])
                i += 9
            else:
                i += 1

    # Tagesseiten
    seitentage = {}
    for sn, txt in seiten.items():
        m = re.search(r"(\d\d\.\d\d\.\d{4}) \((\w\w)\)\s+—\s+([^\n]+)", txt)
        if not m:
            continue
        z = dict(seite=sn, ort=m.group(3).strip())
        for key, pat in (("auswertung", r"Auswertung: ([^\n]+)"), ("einordnung", r"Einordnung: ([^\n]+)"),
                         ("laeq_text", r"LAeq 07–20 Uhr: ([^\n]+)"), ("video", r"VIDEOBELEGE: ([^\n]+)"),
                         ("lauteste", r"Lauteste Std\. ([^\n]+)"), ("wetter", r"WETTEREREIGNISSE[^\n]*\n((?:[^\n]*\n){0,8})")):
            mm = re.search(pat, txt)
            if mm:
                z[key] = mm.group(1).strip()
        seitentage.setdefault(m.group(1), []).append(z)

    with open(os.path.join(ausgabe, "bericht_tage.json"), "w", encoding="utf-8") as f:
        json.dump(tage, f, ensure_ascii=False, indent=1)
    with open(os.path.join(ausgabe, "bericht_tagesseiten.json"), "w", encoding="utf-8") as f:
        json.dump(seitentage, f, ensure_ascii=False, indent=1)
    print(f"{len(d)} Seiten, {len(tage)} Tage in der Tagesuebersicht, {len(seitentage)} Tagesseiten")


if __name__ == "__main__":
    main(*sys.argv[1:3])
