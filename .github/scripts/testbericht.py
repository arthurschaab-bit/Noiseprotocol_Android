#!/usr/bin/env python3
"""Fasst die JUnit-XML-Ergebnisse als Markdown fuer die GitHub-Zusammenfassung zusammen.

Ohne diese Aufbereitung steht das Testergebnis nur im hochgeladenen Artefakt: Man muss es
herunterladen und entpacken, um zu erfahren, welcher Test warum gescheitert ist. Das macht in
der Praxis niemand - stattdessen wird der Test lokal noch einmal ausgefuehrt, und genau das
soll die CI ja ersparen.
"""

import glob
import os
import xml.etree.ElementTree as ET

WURZEL = "app/build/test-results"


def sammle():
    """Liefert (Variante, Suite-Name, Tests, Fehler, [Fehlschlaege]) je XML-Datei."""
    ergebnisse = []
    for pfad in sorted(glob.glob(f"{WURZEL}/*/TEST-*.xml")):
        variante = os.path.basename(os.path.dirname(pfad))
        wurzel = ET.parse(pfad).getroot()
        fehlschlaege = []
        for fall in wurzel.iter("testcase"):
            for kind in fall:
                if kind.tag in ("failure", "error"):
                    erste_zeile = (kind.text or "").strip().split("\n")[0]
                    fehlschlaege.append((fall.get("name"), erste_zeile))
        ergebnisse.append((
            variante,
            (wurzel.get("name") or "").split(".")[-1],
            int(wurzel.get("tests") or 0),
            int(wurzel.get("failures") or 0) + int(wurzel.get("errors") or 0),
            int(wurzel.get("skipped") or 0),
            fehlschlaege,
        ))
    return ergebnisse


def main():
    ergebnisse = sammle()
    if not ergebnisse:
        print("## Testbericht\n")
        print("Keine Testergebnisse gefunden - der Testlauf ist vermutlich vor der Ausführung "
              "abgebrochen (Kompilierfehler?). Siehe Schrittprotokoll.")
        return

    gesamt = sum(e[2] for e in ergebnisse)
    fehler = sum(e[3] for e in ergebnisse)
    uebersprungen = sum(e[4] for e in ergebnisse)

    kopf = "✅" if fehler == 0 else "❌"
    print(f"## {kopf} Testbericht: {gesamt} Tests, {fehler} Fehler"
          + (f", {uebersprungen} übersprungen" if uebersprungen else ""))
    print()

    if fehler:
        print("### Fehlgeschlagene Tests")
        print()
        for variante, suite, _, _, _, fehlschlaege in ergebnisse:
            for name, meldung in fehlschlaege:
                print(f"- **{suite}.{name}** ({variante})")
                if meldung:
                    print(f"  ```\n  {meldung}\n  ```")
        print()

    print("<details><summary>Alle Testklassen</summary>")
    print()
    print("| Variante | Testklasse | Tests | Fehler |")
    print("|---|---|---:|---:|")
    for variante, suite, tests, fehl, _, _ in ergebnisse:
        print(f"| {variante} | {suite} | {tests} | {fehl} |")
    print()
    print("</details>")


if __name__ == "__main__":
    main()
