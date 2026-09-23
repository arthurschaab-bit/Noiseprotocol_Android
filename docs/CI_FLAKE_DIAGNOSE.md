# Emulator-Tests gezielt wiederholen

GitHub zeigt einen neuen `workflow_dispatch`-Workflow erst an, wenn seine Workflow-Datei
auf `main` liegt. Nach dem Merge kann jeder gewünschte Branch als Teststand gewählt werden.

Unter **Actions → Emulator Flake Diagnose → Run workflow** den zu prüfenden Branch wählen,
`tests` mit einer oder mehreren durch Komma getrennten Klassen oder Methoden füllen
(zum Beispiel `ui.HomeScreenInstrumentedTest#filterPanelLaesstSichAufUndZuklappen`),
`wiederholungen` auf 1–100 setzen und den Lauf starten. Der Standard sind 20 Wiederholungen
auf API 34. Vollqualifizierte Namen mit `com.example.lrmprotokoll` funktionieren ebenfalls.
In der Zusammenfassung stehen für jede Auswahl die Fehlerrate, Dauer und die Nummern der
fehlgeschlagenen Iterationen. Das Artefakt `flake-diagnostics-*` enthält deren Logcat,
Screenshot und UI-Hierarchie; es bleibt sieben Tage verfügbar. Schon ein Fehlschlag macht
den Diagnose-Job rot.

Dasselbe per CLI:

```bash
gh workflow run emulator-flake-diagnose.yml --ref feature/ci-flake-diagnose \
  -f tests='ui.HomeScreenInstrumentedTest#filterPanelLaesstSichAufUndZuklappen' \
  -f wiederholungen=20 -f api_level=34
```

## Flake-Policy der PR-Pipeline

Eine fehlgeschlagene Testmethode wird genau einmal in einem neuen App-Prozess wiederholt.
Besteht sie, erscheint sie als **FLAKY** mit einer GitHub-Warnung und Diagnose-Artefakt;
der Job bleibt gemäß Owner-Entscheidung F-1 grün. Scheitert sie erneut, ist sie **FAILED**
und der Job rot. Tests in `.github/flaky-quarantaene.txt` laufen weiterhin und erscheinen
sichtbar, machen den Job aber nicht rot; nur der Owner trägt dort begründete Einträge mit
Issue-Link und Datum ein. Einträge ohne Issue-Link oder älter als 30 Tage erzeugen Warnungen.

**Flaky ist kein Befund, sondern ein Auftrag:** Jeder FLAKY-Test erhält ein Issue, seine Ursache
wird mit dem Diagnose-Workflow untersucht, und Timeouts werden nur nach Messung verändert.
