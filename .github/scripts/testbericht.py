#!/usr/bin/env python3
"""Fasst die JUnit-XML-Ergebnisse als Markdown für die GitHub-Zusammenfassung zusammen.

Unterstützt sowohl JVM-Unit-Tests als auch instrumentierte Android-Emulator-Tests.
"""

import glob
import argparse
import datetime as dt
import os
import sys
import xml.etree.ElementTree as ET


def sammle(suchpfad):
    """Liefert (Variante, Suite-Name, Tests, Fehler, [Fehlschlaege]) je XML-Datei."""
    ergebnisse = []
    for pfad in sorted(glob.glob(os.path.join(suchpfad, '**', 'TEST-*.xml'), recursive=True)):
        try:
            wurzel = ET.parse(pfad).getroot()
        except Exception:
            continue
        variante = os.path.basename(os.path.dirname(pfad))
        suites = [wurzel] if wurzel.tag == 'testsuite' else list(wurzel.iter('testsuite'))
        for suite in suites:
            faelle_der_suite = suite.findall('testcase')
            if not faelle_der_suite:
                continue
            fehlschlaege = []
            for fall in faelle_der_suite:
                for kind in fall:
                    if kind.tag in ("failure", "error"):
                        erste_zeile = (kind.text or "").strip().split("\n")[0]
                        fehlschlaege.append((fall.get("name"), erste_zeile))
            ergebnisse.append((
                variante,
                (suite.get('name') or 'Unbekannt').split('.')[-1],
                len(faelle_der_suite),
                len(fehlschlaege),
                sum(any(kind.tag == 'skipped' for kind in fall) for fall in faelle_der_suite),
                fehlschlaege,
            ))
    return ergebnisse


def faelle(suchpfad):
    """Liest Testmethoden anhand ihrer stabilen Klassen-/Methodenkennung."""
    gefunden = {}
    for pfad in sorted(glob.glob(os.path.join(suchpfad, '**', 'TEST-*.xml'), recursive=True)):
        try:
            wurzel = ET.parse(pfad).getroot()
        except ET.ParseError:
            continue
        for suite in ([wurzel] if wurzel.tag == 'testsuite' else wurzel.iter('testsuite')):
            for fall in suite.findall('testcase'):
                klasse = fall.get('classname') or suite.get('name') or ''
                methode = fall.get('name') or ''
                if not klasse or not methode:
                    continue
                kennung = f'{klasse}#{methode}'
                status = 'FAILED' if any(k.tag in ('failure', 'error') for k in fall) else 'PASSED'
                gefunden[kennung] = status
    return gefunden


def wiederholungen_lesen(pfad):
    ergebnisse = {}
    if not pfad or not os.path.exists(pfad):
        return ergebnisse
    with open(pfad, encoding='utf-8') as datei:
        for zeile in datei:
            teile = zeile.rstrip('\n').split('\t')
            if len(teile) == 2 and teile[1] in ('PASSED', 'FAILED'):
                ergebnisse[teile[0]] = teile[1]
    return ergebnisse


def schreibe_einzeltest(pfad, kennung, bestanden, ausgabe):
    """Ergaenzt die separat gestarteten Berechtigungstests zur JUnit-Testanzahl."""
    klasse, methode = kennung.split('#', 1)
    suite = ET.Element('testsuite', name=klasse, tests='1',
                       failures='0' if bestanden else '1', errors='0')
    fall = ET.SubElement(suite, 'testcase', classname=klasse, name=methode)
    if not bestanden:
        ET.SubElement(fall, 'failure', message='Erstversuch fehlgeschlagen').text = ausgabe
    ET.SubElement(fall, 'system-out').text = ausgabe
    os.makedirs(os.path.dirname(pfad), exist_ok=True)
    ET.ElementTree(suite).write(pfad, encoding='utf-8', xml_declaration=True)


def quarantaene_lesen(pfad):
    eintraege = {}
    warnungen = []
    if not pfad or not os.path.exists(pfad):
        return eintraege, warnungen
    with open(pfad, encoding='utf-8') as datei:
        for nummer, zeile in enumerate(datei, 1):
            if not zeile.strip() or zeile.lstrip().startswith('#'):
                continue
            teile = [teil.strip() for teil in zeile.split('|')]
            if len(teile) != 4:
                warnungen.append(f'Quarantaene Zeile {nummer}: erwartet Klasse#Methode | Issue-Link | Datum | Begruendung.')
                continue
            kennung, issue, datum, begruendung = teile
            eintraege[kennung] = (issue, datum, begruendung)
            if not issue.startswith(('https://github.com/', 'http://github.com/')):
                warnungen.append(f'Quarantaene {kennung}: Issue-Link fehlt oder ist ungueltig.')
            try:
                alter = (dt.date.today() - dt.date.fromisoformat(datum)).days
                if alter > 30:
                    warnungen.append(f'Quarantaene {kennung}: Eintrag ist {alter} Tage alt.')
            except ValueError:
                warnungen.append(f'Quarantaene {kennung}: Datum muss JJJJ-MM-TT sein.')
    return eintraege, warnungen


def instrumentierte_auswertung(suchpfad, retries, quarantine):
    tests = faelle(suchpfad)
    erneut = wiederholungen_lesen(retries)
    liste, warnungen = quarantaene_lesen(quarantine)
    # Manuell gestartete Berechtigungstests schreiben nur in die Retry-Datei.
    fehler = {test for test, status in tests.items() if status == 'FAILED'} | set(erneut)
    flaky = sorted(test for test in fehler if erneut.get(test) == 'PASSED')
    failed = sorted(test for test in fehler if erneut.get(test) != 'PASSED' and test not in liste)
    quarantined = sorted(test for test in (set(tests) | set(erneut)) if test in liste)
    return tests, flaky, failed, quarantined, warnungen


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    if len(sys.argv) == 5 and sys.argv[1] == '--record-direct':
        _, _, kennung, status, ausgabepfad = sys.argv
        if '#' not in kennung or status not in ('PASSED', 'FAILED'):
            sys.exit('Ungueltiger direkter Testfall.')
        with open(ausgabepfad, encoding='utf-8', errors='replace') as datei:
            ausgabe = ''.join(c for c in datei.read() if c in '\t\n\r' or ord(c) >= 32)
        klasse = kennung.split('#', 1)[0]
        pfad = os.path.join('app/build/outputs/androidTest-results/permission',
                            f'TEST-{klasse}.xml')
        schreibe_einzeltest(pfad, kennung, status == 'PASSED', ausgabe)
        return

    if len(sys.argv) > 1 and sys.argv[1] in ('--failed', '--status', '--count'):
        modus = sys.argv[1]
        suchpfad = sys.argv[2]
        if modus == '--failed':
            for test, status in sorted(faelle(suchpfad).items()):
                if status == 'FAILED':
                    print(test)
            return
        if modus == '--count':
            tests = faelle(suchpfad)
            print(f'{len(tests)} eindeutige Methoden, '
                  f'{sum(e[2] for e in sammle(suchpfad))} Testeintraege')
            return
        tests, flaky, failed, _, warnungen = instrumentierte_auswertung(
            suchpfad, sys.argv[3], sys.argv[4])
        if not tests:
            print('::error::Keine JUnit-Testmethoden gefunden; Testlauf nicht als gruen bewertbar.')
            sys.exit(1)
        for test in flaky:
            print(f'::warning::{test} ist FLAKY (bei einmaliger Wiederholung bestanden).')
        for warnung in warnungen:
            print(f'::warning::{warnung}')
        for test in failed:
            print(f'::error::{test} ist FAILED (auch bei Wiederholung fehlgeschlagen).')
        if failed:
            sys.exit(1)
        return

    titel = sys.argv[1] if len(sys.argv) > 1 else "Testbericht"
    suchpfad = sys.argv[2] if len(sys.argv) > 2 else "app/build/test-results"
    retries = sys.argv[4] if len(sys.argv) > 4 and sys.argv[3] == '--retries' else None
    quarantine = sys.argv[6] if len(sys.argv) > 6 and sys.argv[5] == '--quarantine' else None

    ergebnisse = sammle(suchpfad)
    if not ergebnisse and retries is None:
        print(f"## {titel}\n")
        print("Keine Testergebnisse gefunden - der Testlauf ist vermutlich vor der Ausführung "
              "abgebrochen (Kompilierfehler?). Siehe Schrittprotokoll.")
        return

    gesamt = sum(e[2] for e in ergebnisse)
    fehler = sum(e[3] for e in ergebnisse)
    uebersprungen = sum(e[4] for e in ergebnisse)

    if retries is not None:
        _, flaky, failed, quarantined, warnungen = instrumentierte_auswertung(
            suchpfad, retries, quarantine)
    else:
        flaky, failed, quarantined, warnungen = [], [], [], []
    kopf = "✅" if (not failed if retries is not None else fehler == 0) else "❌"
    fehler_text = f'{fehler} anfangs fehlgeschlagen' if retries is not None else f'{fehler} Fehler'
    print(f"## {kopf} {titel}: {gesamt} Tests, {fehler_text}"
          + (f", {uebersprungen} übersprungen" if uebersprungen else ""))
    print()

    if retries is not None:
        for ueberschrift, namen in (
            ('Fehlgeschlagen', failed),
            ('Flaky (bei Wiederholung bestanden)', flaky),
            ('In Quarantäne', quarantined),
        ):
            print(f'### {ueberschrift}\n')
            if namen:
                for name in namen:
                    print(f'- `{name}`')
            else:
                print('Keine.')
            print()
        for warnung in warnungen:
            print(f'> ⚠ {warnung}')
        if warnungen:
            print()
    elif fehler:
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
