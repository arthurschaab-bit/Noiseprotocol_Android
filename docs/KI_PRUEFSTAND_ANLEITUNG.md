# KI-Prüfstand: Anleitung und Stand (04.11.2026)

Diese Anleitung beschreibt die Werkzeuge unter `tools/ki_pruefstand/`, wie sie zusammenhängen, wie man sie
aufruft und was bekannt unvollständig ist. Die Ergebnisse stehen in `docs/BERICHT_KI_PRUEFSTAND.md`.
Es wurde **kein App-Code** geändert.

## 1 · Ziel

Zu jeder Aufnahme der Lärmprotokoll-App sagen, **ob** sie von der Baustelle kommt und **was für ein Geräusch** es
war, und daraus je Tag ein kurzes **Textprotokoll** machen ("12:30–15:45 ständig wiederkehrende Motorgeräusche,
zusammen ca. 38 min"). Der dB(A)-Verlauf des PCE-323 dient als Rechengrundlage für Dauer und Pausen. Die
Pegelwerte selbst stehen im Schallbericht und werden im Text nicht wiederholt.

## 2 · Datenfluss

| Schritt | Skript | Ergebnis |
|---|---|---|
| Kandidaten für die Erstprüfung wählen | `auswahl.py` | Liste (lokal) |
| WAVs aus Stundenzips holen | `wavs_holen.py`, `ersatz.py` | WAVs (lokal) |
| Erstes Owner-Urteil, 43 Clips | `bestaetigung_seite.py` | Artifact-Seite, Urteile im Seitenspeicher |
| YAMNet ohne native Bibliothek | `yamnet_numpy.py` | Scores und Embeddings |
| Alle Ereignisse ab 60 dB (Drive `Lärmprotokoll`) | `batch_analyse.py` | `shards/*.npz`, `ereignisse.csv` |
| Ältere Aufnahmen Juni–August (Bautagebuch) | `batch_alt.py` | `shards/*.npz`, `ereignisse.csv`, `pegel/*.npy` |
| Detailtabelle und Klanggruppen | `tabelle.py` | `detail.csv`, `cluster_uebersicht.md` |
| Hörseiten je Klanggruppe | `gruppen_seiten.py` | 5 Artifact-Seiten à 30 Clips |
| Auswertung der Ansätze | `auswerten.py`, `urteile_auswerten.py` | Kennzahlen (JSON) |
| Gruppen für leise und ältere Aufnahmen | `gruppen_bilden.py`, `gruppen_seiten.py` | 10 Hörseiten (60 Gruppen) |
| Urteile in Protokolleingaben übersetzen | `protokoll_vorbereiten.py` | `detail.csv`, `gruppen_urteile.json` |
| Tagesprotokoll | `tagesprotokoll.py`, `protokoll_zusammenfuehren.py` | `tagesprotokoll.md`, `.csv`, `protokoll_gesamt.md` |

Alle Rohdaten, Embeddings, WAVs, Urteile und Protokolle liegen **lokal** unter `%LOCALAPPDATA%\ki_pruefstand`
und nicht im Repo (Aufnahmenamen, Standortbezug).

## 3 · Einrichtung

```powershell
# Python 3.11, Umgebung außerhalb des Repos
py -3.11 -m venv $env:LOCALAPPDATA\ki_pruefstand\venv
& $env:LOCALAPPDATA\ki_pruefstand\venv\Scripts\pip install -r tools\ki_pruefstand\requirements.txt
```

Warum `yamnet_numpy.py`: Auf dem Owner-PC blockiert die Windows-Anwendungssteuerung die nativen Bibliotheken von
LiteRT und TensorFlow ("Eine Anwendungssteuerungsrichtlinie hat diese Datei blockiert"), Docker startete nicht.
Der Nachbau rechnet das int8-Modell `yamnet.tflite` in NumPy und stimmt am Referenzclip mit der App überein
(`Rail transport` 0,59, `Engine` 0,33, `Jackhammer` unter 0,01). Er kann um eine Quantisierungsstufe von TFLite abweichen.

## 4 · Aufrufe

```powershell
$P = "$env:LOCALAPPDATA\ki_pruefstand"; $PY = "$P\venv\Scripts\python.exe"; cd tools\ki_pruefstand

# 1) Neue Aufnahmen aus dem Drive (alle Ereignisse ab 60 dB), fortsetzbar
& $PY batch_analyse.py "F:\Meine Ablage\Lärmprotokoll" "$P\voll" --min-db 60 --workers 4

# 2) Ältere Aufnahmen (Zips Juni–August), Prozesszahl passt sich selbst an
& $PY batch_alt.py "F:\Meine Ablage\Baustelle\400_Bautagebuch\Schallmessung" "$P\alt" --min-db 60 --workers 6

# 3) Detailtabelle und Klanggruppen (gruppiert werden nur Clips ab 75 dB)
& $PY tabelle.py "F:\Meine Ablage\Lärmprotokoll" "$P\voll" "$P\labels.json" --cluster 30 --cluster-ab-db 75

# 4) Hörseiten für die Gruppen (WAVs werden eingebettet; Ausgabe nicht ins Repo)
& $PY gruppen_seiten.py "F:\Meine Ablage\Lärmprotokoll" "$P\voll\detail.csv" "$P\gruppen_wavs" "$P\seiten"

# 5) Auswertung auf den Owner-Urteilen
& $PY urteile_auswerten.py "$P\voll" "$P\gruppen_urteile.json" "$P\labels.json" "$P\ergebnis_urteile.json"

# 6) Tagesprotokoll
& $PY tagesprotokoll.py "$P\voll" "$P\gruppen_urteile.json" "$P\seiten\zuordnung.json" "$P\protokoll" --drive "F:\Meine Ablage\Lärmprotokoll"
```

## 5 · Zuverlässigkeit der Stapelläufe

- **Fortsetzbar:** Ergebnisse werden als Pakete (`shards/`) geschrieben. Ein erneuter Aufruf überspringt fertige Pakete.
  Zwischendateien tragen die Prozess-ID, zwei Instanzen stören sich nicht.
- **Wiederholungen bei Drive-Fehlern** (`OSError`, unvollständige Zips): Wartezeiten 1, 5, 10 und 60 Minuten
  (`--wartezeiten`). Danach wird das Paket zurückgestellt und beim nächsten Durchgang erneut versucht.
- **`batch_alt.py` läuft, bis alles fertig ist:** Es arbeitet in Durchgängen mit Wartezeit dazwischen (`--max-durchgaenge`),
  prüft am Ende jedes Paket auf Vollständigkeit (analysierte plus defekte Clips = erwartete Clips) und schreibt erst dann
  `FERTIG.txt`. Bei Misserfolg endet es mit Exit-Code 2 und nennt die offenen Pakete.
- **Prozesszahl passt sich an:** Alle `--pruef-minuten` (10) misst das Skript CPU-Last und freien Arbeitsspeicher. Bei weniger
  als 2,5 GB frei sinkt die Zahl um 2, bei CPU über 93 % um 1, bei mehr als 4 GB frei und CPU unter 80 % steigt sie
  (Bereich `--min-workers` bis `--max-workers`). Die Arbeitspakete haben höchstens `--chunk` (300) Clips, damit die Änderung
  schnell greift. Der Zustand steht in `status.json`.
- **Hängende Pakete** werden nach `--job-timeout-min` (90) beendet und wiederholt.
- **Nicht abfangbar:** Wenn ein Zugriff auf den Drive ohne Fehler blockiert, helfen die Wiederholungen nicht. Ebenso kann
  Claude Code eine Hintergrundaufgabe bei kritischem Speichermangel beenden. Der Lauf ist dann fortsetzbar, muss aber neu
  gestartet werden.

## 6 · Klassen

Zielklassen sind die 13 Feinklassen aus `Baul-rm` (`src/baul_rm/audio_model/schema.py`, Schema `baulaerm-multilabel-v1`).
Die Owner-Urteile zu den Klanggruppen wurden so abgebildet:

| Urteil | Feinklasse |
|---|---|
| Motor, Bagger | B05 Schweres Baugerät |
| Motor, Tiefbohrer | B01 Tief-/Drehbohrgerät |
| Hämmernd (Drucklufthammer, Hydraulikhammer) | B02 Abbruch-/Schlagmaschine |
| Schlag, Klirren | B09 Materialimpuls |
| Bohren | B03 Bohren/Fräsen/Schleifen |
| Motor mit sägend/schleifend | B04 plus B06 |
| Signal (Rückfahrwarner) | B08 Warnsignal |
| Sirene, Messgerät anbringen, keine Baustelle | U04 Sonstige Umgebung |

Gruppenurteile gelten für alle Clips der Gruppe als **wahrscheinlich**, direkt gehörte Clips als **bestätigt**. Mischgruppen
(5, 6, 18, 26) ordnet ein Modell zu (logistische Regression auf YAMNet-Embeddings, Schwelle 0,6).

## 7 · Protokollformat

Der Text folgt dem Schreibstil des Bautagebuchs des Owners (`Bautagebuch_Schaab_V24.xlsx`): knappe Einträge, Quelle zuerst, Uhrzeiten
als "ca. 07:15", "ganztägig", "Zusätzlich …", "Zeitweise …" und am Ende "Messwerte s. Schallbericht TT.MM.". Benennung wie dort:
Tiefbohrer, Presslufthammer, Baggergeräusche, Rückfahrwarner. Pegelzahlen stehen nicht im Text (siehe Schallbericht).

> **Mittwoch, 17.06.2026 (Messzeit ca. 06:45 bis 18:00 Uhr)**
> Tiefbohrer (Motor-, Schleif- und Klirrgeräusche) ganztägig (ca. 06:45–16:30 Uhr), nahezu ununterbrochen. Zusätzlich Baggergeräusche ganztägig (ca. 07:00–16:15 Uhr), ständig wiederkehrend. Messwerte s. Schallbericht 17.06.

Zusätzlich entsteht `bautagebuch_vorschlag.csv` in den Spalten des Bautagebuchs (Datum; Wochentag; Wahrnehmungszeitraum; Bauphase;
Beschreibung / Geräusche; Einschränkung; Sonderbelege). Bauphase, Einschränkung und Sonderbelege bleiben leer, sie sind persönliche
Angaben des Owners ("[bitte ergänzen]").

- Aufnahmen derselben Klasse bis 60 s Abstand bilden eine Episode, Episoden bis 60 Minuten Abstand einen Zeitraum (`--blockluecke`);
  vereinzelte kurze Episoden am Rand werden abgeschnitten (kein "bis 24:00" wegen einer einzelnen Nachtaufnahme).
- Die Dichte steht als Ausdruck: nahezu ununterbrochen (aktive Zeit ab 50 % des Zeitraums), ständig wiederkehrend (12 bis 50 %),
  immer wieder (3 bis 12 %), gelegentlich (darunter). "Ganztägig" steht, wenn ein Zeitraum mindestens 8 Stunden dauert.
- Die Dauer folgt dem dB(A)-Verlauf des PCE-323: Jede Episode wird nach beiden Seiten verlängert, solange der Pegel höchstens 8 dB unter
  dem typischen Clip-Pegel der Klasse liegt (mindestens 60 dB, höchstens 30 s je Seite).
- Im Text stehen nur Baustellenklassen. Umgebungsgeräusche, "Anbringen des Messgerätes" und nicht näher bestimmte Baustellengeräusche
  stehen nur in der CSV (`tagesprotokoll.csv`), ebenso die genauen Werte. Rückfahrwarner zählen zur Baustelle (Owner, 10.11.2026).
- **Tage ohne nennenswerte PCE-Messwerte (unter 30 Minuten) werden nicht bearbeitet.** Das betraf 14 Tage zwischen 15.09. und 06.10.2026.
- Vergleich mit dem Bautagebuch (15 gemeinsame Tage, 08.06.–03.07.2026): Bis 25.06. stimmen Tiefbohrer-Tage und Zeiten gut überein. Der Eintrag
  des Owners beschreibt die Wahrnehmung (zu Hause), das Protokoll den ganzen Baustellenbetrieb. Abweichungen am 08.06. (Pause bis 13:00
  im Tagebuch, im Protokoll durchgehend) und 29.06.–03.07. (Tagebuch: morgens Bauschuttabholung mit Bagger, Protokoll: Tiefbohrer fast
  ganztägig) sind zu prüfen.

## 8 · Bekannte Grenzen (Stand 10.11.2026)

- **Klassifiziert sind alle Aufnahmen ab 60 dB, soweit die Klanggruppe beurteilt wurde.** Sieben Gruppen der leisen älteren Aufnahmen
  (`AQ12`, `AQ13`, `AQ15`–`AQ19`, zusammen 6 924 Clips) haben noch kein Urteil und werden nicht klassifiziert.
- Die Zeiten sind **Schätzungen**. Die App zeichnet nur auf, wenn der Pegel die Schwelle überschreitet. Dauergeräusche mehrerer
  Klassen überlappen in der Zeit, die Summe über Klassen kann die tatsächlich laute Zeit übersteigen.
- Das Gruppenurteil des Owners gilt für alle Clips der Gruppe (nur 5 Clips je Gruppe wurden gehört). Mischgruppen ordnet ein Modell zu
  (logistische Regression auf YAMNet-Embeddings, Schwelle 0,6, Training mit höchstens 4 000 Clips je Klasse).
- Motorgruppen ohne bekanntes Gerät werden mit dem Bagger-Motor zusammen dargestellt ("Motorgeräusche (Bagger)").
- Im Text stehen höchstens fünf Klassenzeilen je Tag (`--max-zeilen`), der Rest nur in der CSV.
- **Fehlalarme** sind kaum messbar: Es gibt nur wenige Gegenbeispiele (kein Verkehr, Zug oder Nachbarn zur Arbeitszeit).
- Die Juni–August-Daten stammen von anderer Position und älterer App-Version. Cluster aus beiden Quellen sind getrennt zu betrachten.
- `Baul-rm` hat eigene, noch nicht vom Owner geprüfte Cluster für Juni und Juli. Ein Abgleich mit dieser Arbeit steht aus.
