# Auftrag: Datenbanksicherung als Vollsicherung plus kumulative Teilsicherung

**Auslöser:** Befund 2 in [`BEFUNDE_BUNDLES_2026-10-10.md`](BEFUNDE_BUNDLES_2026-10-10.md).

- Die Sicherung lud alle 30–40 Minuten die **komplette** Datenbank hoch: bis 302 MB pro Sicherung,
  bis 8,1 GB am Tag.
- Rund ein Drittel der Versuche scheiterte („Drive nicht erreichbar“, „Job was cancelled“).
- Rund drei Viertel der Datei sind `level_samples`, der Rohwert-Puffer für den Drive-Sync.

Damit ist die offene Entscheidung **E1** aus
[`PROMPT_UNTERSUCHUNG_HINTERGRUNDJOBS_DRIVE.md`](PROMPT_UNTERSUCHUNG_HINTERGRUNDJOBS_DRIVE.md)
entschieden.

## 1 · Ziel des Owners

Nach einem Fehler soll der **letzte gültige Stand** wiederherstellbar sein, nicht der von vor drei
Tagen. Die Häufigkeit war also richtig; zu groß war der Inhalt.

## 2 · Owner-Entscheidungen (10.10.2026)

| Nr. | Frage | Entscheidung |
|---|---|---|
| S1 | Grundform | Vollsicherung plus Teilsicherungen (Variante c) |
| S2 | Zuschnitt der Teilsicherung | kleine Tabellen jedes Mal komplett, `measurements` nur der Zuwachs |
| S3 | Vollsicherung | einmal pro Tag, **03:00**, **nur im WLAN** |
| S4 | Aufbewahrung der Teilsicherung | bis eine Vollsicherung sie ersetzt |
| S5 | `level_samples` sichern? | nein; die 20-Hz-Mikrofonwerte stehen verdichtet in den Tages-CSVs, die PCE-323-Werte zusätzlich in `measurements` |
| S6 | Kumulativ oder Kette | **kumulativ**: genau eine Teilsicherungsdatei, alle 30 min überschrieben |

## 3 · Umsetzung

### 3.1 Vollsicherung

- **Wer:** `drive/DatenbankVollsicherungWorker.kt`, täglich um 03:00, Bedingung
  `NetworkType.UNMETERED`.
- **Wie:** als Vordergrundarbeit (`dataSync`), weil der Upload die 10-Minuten-Grenze überschreiten
  kann.
- **Datei:** `BACKUP/laermprotokoll_datenbank.zip`, im bisherigen Format.
- **Was neu ist:**
  - `level_samples` wird **in einer Kopie** geleert und die Kopie per `VACUUM` verkleinert. Die
    laufende Datenbank bleibt unverändert.
  - Das Manifest trägt zusätzlich `art = VOLL`, `vollsicherungId` und `schemaVersion`.
- **Nach dem Upload:** Erst bei Erfolg merkt sich die App die Kennung und den Stand
  (`SettingsManager.merkeVollsicherung`): die höchste `id` in `measurements` und
  `klassifikations_rohdaten`.
- **Erste Sicherung:** Gibt es noch keine Vollsicherung, läuft einmalig eine, sobald WLAN da ist.

### 3.2 Teilsicherung

- **Wer:** der bisherige 30-Minuten-Schritt im Drive-Sync (`ladeDatenbankSicherungHoch`).
- **Datei:** `BACKUP/laermprotokoll_teilsicherung.zip`, jedes Mal überschrieben.
- **Inhalt:** eine eigene SQLite-Datei (`SicherungsDatenbank.baueTeilsicherung`):
  - alle Tabellen außer `level_samples` und den internen Tabellen von SQLite und Room;
  - `measurements` und `klassifikations_rohdaten` nur mit `id` über dem Stand der Vollsicherung;
  - alle übrigen Tabellen vollständig, weil sich dort auch bestehende Zeilen ändern (Labels,
    Session-Ende, Papierkorb).
- **Ohne Vollsicherung** gibt es keine Teilsicherung.

### 3.3 Wiederherstellen von Drive

- Die App lädt beide Dateien herunter und spielt zuerst die Vollsicherung ein.
- Die Teilsicherung kommt nur dazu, wenn ihre `basisVollsicherungId` und `schemaVersion` passen:
  - kleine Tabellen werden ersetzt;
  - `measurements` wird ergänzt;
  - `klassifikations_rohdaten` wird je Aufnahme ersetzt.
- Das Einspielen läuft in einer Transaktion. Passt die Teilsicherung nicht oder scheitert sie, gilt
  die Vollsicherung allein, und der Nutzer bekommt einen entsprechenden Hinweis.

### 3.4 Unverändert

- Die manuelle Sicherung über eine Datei (F13) sichert weiterhin alles, einschließlich
  `level_samples`.

## 4 · Bekannte Grenzen

- **Speicherbedarf:** Für die Vollsicherung braucht die App vorübergehend zusätzlich eine volle
  Kopie der Datenbank im `cacheDir`. Das wird vorab geprüft.
- **Zwei Teilsicherungen auf Drive:** Die Drive-Schnittstelle der App kann keine Dateien löschen.
  Die Teilsicherung einer älteren Vollsicherung bleibt deshalb liegen, bis der nächste Sync sie
  überschreibt. Beim Wiederherstellen wird sie über die Kennung erkannt und übergangen.
- **Rohwerte:** Nach einer Wiederherstellung fehlen die Mikrofon-Rohwerte seit dem letzten
  Drive-Sync, höchstens rund 30 Minuten.

## 5 · Gerätecheck

Siehe `CHECKLISTE_GERAETETEST.md` Teil F, Eintrag F30.
