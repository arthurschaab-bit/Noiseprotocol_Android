# Auftrag: KI-Batch im Hintergrund und nachts

**Auslöser:** Owner-Befund vom 10.10.2026 auf dem Huawei P30: „KI-Batch-Verarbeitung läuft nur mit
geöffnetem Bildschirm.“ Die Einordnung steht in [`BEFUNDE_BUNDLES_2026-10-10.md`](BEFUNDE_BUNDLES_2026-10-10.md).

## 1 · Ursache

- „Alle klassifizieren“ (Menü) und der Tagesknopf auf der Startseite riefen
  `klassifiziereUndSpeichere()` in `rememberCoroutineScope()` von `NoiseProtocolApp` auf
  (`MainActivity.kt`).
- Das ist keine Hintergrundarbeit. Folgen:
  - Ist der Bildschirm gesperrt, darf das System die App drosseln.
  - Schließt der Nutzer die App, bricht der Batch ab.
  - Die blockierende MediaPipe-Inferenz lief auf dem Main-Thread.
- Indiz: `klassifikations_rohdaten` hatte in allen drei P30-Bundles 0 Zeilen.

## 2 · Owner-Entscheidungen (10.10.2026)

| Nr. | Frage | Entscheidung |
|---|---|---|
| E1 | Gerät des Befunds | Huawei P30 (Android 10) |
| E2 | Weiterlaufen nach Schließen der App | ja |
| E3 | Abbrechen-Knopf in der Benachrichtigung | ja |
| E4 | Berechtigung `FOREGROUND_SERVICE_DATA_SYNC` | ja |
| E5 | Neue Labels nach Drive | ja, als eigene Nachtragsdatei je Tag (Variante A, siehe 3.3) |
| E6 | Nachtlauf | jede Nacht alle unklassifizierten Aufnahmen, auch bei gesperrtem Bildschirm; Einstellung, Default an |

## 3 · Umsetzung

### 3.1 Worker

- **Neu: `audio/KiBatchWorker.kt`** (`CoroutineWorker`):
  - Bestimmt die Kandidaten selbst über `unklassifizierteAufnahmen()`. Das Ergebnis lässt sich
    optional per `von`/`bis` auf einen Tag eingrenzen.
  - Läuft als Vordergrundarbeit (`dataSync`) mit Fortschritt in der Benachrichtigung und einem
    Abbrechen-Knopf (`WorkManager.createCancelPendingIntent`).
  - Verweigert das System den Vordergrunddienst, läuft der Batch als gewöhnliche
    Hintergrundarbeit weiter.
- **Abbruch:** `klassifiziereUndSpeichere()` prüft vor jeder Aufnahme, ob abgebrochen wurde.
  Was bis dahin klassifiziert ist, bleibt gespeichert und wird für Drive vorgemerkt.

### 3.2 Auslöser

- **Menü und Tagesknopf** starten `KiBatchPlanung.starteJetzt()` (eindeutiger Name `ki_batch`,
  `KEEP`, expedited). Die Startseite zeigt Fortschritt und Abschlussmeldung aus den `WorkInfo`s.
- **Nachtlauf:** `KiBatchPlanung.planeNachtlauf()`, täglich ab 01:30 Uhr. Das liegt vor der
  geplanten Vollsicherung um 03:00, damit deren Stand die neuen Labels enthält.
  - Bedingung: Akku nicht niedrig.
  - Wird bei jedem App-Start und beim Umschalten geplant oder entfernt.
  - Wirkt nur, wenn die KI nicht ganz ausgeschaltet ist (`aiMode != "OFF"`).
- **Einstellung:** „Nachts automatisch klassifizieren“ in der KI-Karte
  (`SettingsManager.kiNachtlauf`, Default an).

### 3.3 Drive-Nachtrag (E5, Variante A)

- Die Tages-CSV wird **nicht** neu erzeugt. Die Rohwerte eines Tages sind drei Tage nach dem Sync
  gelöscht; eine neu erzeugte CSV überschriebe die gute in Drive mit einer ohne Pegel.
- **Stattdessen:** `klassifikation_nachtrag_JJJJ-MM-TT.csv` im Ordner `Schallmessung` des Tages.
  - Spalten: `Zeit;Aufnahme;Pegel_dB;Klassifikation`
  - Je Aufnahme mit KI-Entscheidung eine Zeile; „nicht erkannt“ steht ausdrücklich da.
- **Vormerkung:** `SettingsManager.kiNachtragOffeneTage`. Ein Tag bleibt vorgemerkt, bis sein
  Upload gelungen ist. Ein erneuter Nachtrag ersetzt die Datei.

## 4 · Bewusst nicht in diesem Auftrag

- **„Neu bewerten“** im Menü läuft weiterhin im Bildschirm-Scope. Es bewertet bereits
  klassifizierte Aufnahmen aus den gespeicherten Rohdaten neu, ohne erneute Inferenz.
- **Datenbanksicherung** (Vollsicherung 03:00 nur im WLAN, kumulative Teilsicherung): eigener
  Auftrag.

## 5 · Gerätecheck

Siehe `CHECKLISTE_GERAETETEST.md` Teil F, Eintrag F29.
