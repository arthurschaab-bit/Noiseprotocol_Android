#!/usr/bin/env python3
"""Fasst die JUnit-XML-Ergebnisse als Markdown für die GitHub-Zusammenfassung zusammen.

Unterstützt sowohl JVM-Unit-Tests als auch instrumentierte Android-Emulator-Tests.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET


def sammle(suchpfad):
    """Liefert (Variante, Suite-Name, Tests, Fehler, [Fehlschlaege]) je XML-Datei."""
    ergebnisse = []
    suchmuster = [
        os.path.join(suchpfad, "TEST-*.xml"),
        os.path.join(suchpfad, "*", "TEST-*.xml"),
        os.path.join(suchpfad, "*", "*", "TEST-*.xml"),
        os.path.join(suchpfad, "*", "*", "*", "TEST-*.xml"),
    ]
    gefundene_dateien = set()
    for muster in suchmuster:
        for pfad in glob.glob(muster):
            gefundene_dateien.add(pfad)

    for pfad in sorted(gefundene_dateien):
        try:
            wurzel = ET.parse(pfad).getroot()
        except Exception:
            continue
        
        # Testsuite Name
        suite_name = (wurzel.get("name") or "").split(".")[-1]
        if not suite_name and wurzel.tag == "testsuites":
            # Einige Formate kapseln testsuite-Tags
            for subsuite in wurzel.findall("testsuite"):
                suite_name = (subsuite.get("name") or "").split(".")[-1]
                break

        variante = os.path.basename(os.path.dirname(pfad))
        fehlschlaege = []
        for fall in wurzel.iter("testcase"):
            for kind in fall:
                if kind.tag in ("failure", "error"):
                    erste_zeile = (kind.text or "").strip().split("\n")[0]
                    fehlschlaege.append((fall.get("name"), erste_zeile))
        
        tests = int(wurzel.get("tests") or 0)
        fehler = int(wurzel.get("failures") or 0) + int(wurzel.get("errors") or 0)
        skipped = int(wurzel.get("skipped") or 0)

        if tests > 0 or fehler > 0 or fehlschlaege:
            ergebnisse.append((
                variante,
                suite_name or "Unbekannt",
                tests,
                fehler,
                skipped,
                fehlschlaege,
            ))
    return ergebnisse


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    titel = sys.argv[1] if len(sys.argv) > 1 else "Testbericht"
    suchpfad = sys.argv[2] if len(sys.argv) > 2 else "app/build/test-results"

    ergebnisse = sammle(suchpfad)
    if not ergebnisse:
        print(f"## {titel}\n")
        print("Keine Testergebnisse gefunden - der Testlauf ist vermutlich vor der Ausführung "
              "abgebrochen (Kompilierfehler?). Siehe Schrittprotokoll.")
        return

    gesamt = sum(e[2] for e in ergebnisse)
    fehler = sum(e[3] for e in ergebnisse)
    uebersprungen = sum(e[4] for e in ergebnisse)

    kopf = "✅" if fehler == 0 else "❌"
    print(f"## {kopf} {titel}: {gesamt} Tests, {fehler} Fehler"
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
