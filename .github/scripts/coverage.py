#!/usr/bin/env python3
"""Fasst den Kover-XML-Report (JaCoCo-Format) als Markdown fuer die GitHub-Zusammenfassung
zusammen - insbesondere die Gesamt-Line-Coverage als eine einzelne, klar lesbare Zahl.
"""

import sys
import xml.etree.ElementTree as ET


def lies_gesamtcounter(pfad):
    """Liefert {typ: (missed, covered)} fuer die Counter auf Report-Ebene (Gesamtsumme ueber
    alle Pakete/Klassen) - nicht die Counter je Paket, die im selben XML ebenfalls vorkommen."""
    wurzel = ET.parse(pfad).getroot()
    ergebnis = {}
    for counter in wurzel.findall("counter"):
        typ = counter.get("type")
        missed = int(counter.get("missed") or 0)
        covered = int(counter.get("covered") or 0)
        ergebnis[typ] = (missed, covered)
    return ergebnis


def prozent(missed, covered):
    gesamt = missed + covered
    if gesamt == 0:
        return None
    return covered / gesamt * 100


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    pfad = sys.argv[1] if len(sys.argv) > 1 else "app/build/reports/kover/reportDebug.xml"

    try:
        counter = lies_gesamtcounter(pfad)
    except FileNotFoundError:
        print("## Coverage\n")
        print(f"Kein Kover-Report unter `{pfad}` gefunden - der Report-Task ist vermutlich "
              "nicht gelaufen (Kompilier- oder Testfehler davor?). Siehe Schrittprotokoll.")
        return
    except Exception as e:
        print("## Coverage\n")
        print(f"Kover-Report unter `{pfad}` konnte nicht gelesen werden: {e}")
        return

    zeilen_missed, zeilen_covered = counter.get("LINE", (0, 0))
    zeilen_prozent = prozent(zeilen_missed, zeilen_covered)

    if zeilen_prozent is None:
        print("## Coverage\n")
        print("Keine Zeilen im Report - vermutlich lief keine einzige Testklasse durch.")
        return

    print(f"## Coverage: {zeilen_prozent:.1f}% Line-Coverage\n")
    print(f"{zeilen_covered} von {zeilen_covered + zeilen_missed} Zeilen abgedeckt.\n")

    print("| Metrik | Abgedeckt | Gesamt | Anteil |")
    print("|---|---:|---:|---:|")
    for typ, label in (
        ("LINE", "Zeilen"),
        ("INSTRUCTION", "Instruktionen"),
        ("BRANCH", "Verzweigungen"),
        ("METHOD", "Methoden"),
        ("CLASS", "Klassen"),
    ):
        if typ not in counter:
            continue
        missed, covered = counter[typ]
        anteil = prozent(missed, covered)
        anteil_text = f"{anteil:.1f}%" if anteil is not None else "-"
        print(f"| {label} | {covered} | {covered + missed} | {anteil_text} |")


if __name__ == "__main__":
    main()
