# KI-Prüfstand

Python-Skripte (nur Standardbibliothek bisher) für den Vergleich von Erkennungsansätzen.
Keine WAVs, Kandidatenlisten oder Drive-Pfade im Repo.

1. `auswahl.py <Drive-Wurzel> <Kandidaten.csv>` — Schritt A, Kandidaten aus den Nachtrag-CSVs.
2. `wavs_holen.py <Drive-Wurzel> <Kandidaten.csv> <Zielordner>` — Schritt B, WAVs aus den Stundenzips (Zielordner außerhalb des Repos).
3. `ersatz.py <Drive-Wurzel> <Kandidaten.csv> <wav>...` — ersetzt Kandidaten, deren WAV nicht in den Zips liegt (`PYTHONPATH=. python -s ersatz.py ...`).
4. `bestaetigung_seite.py <Kandidaten.csv> <WAV-Ordner> <Ausgabe.html>` — Schritt C, Seite für die Owner-Bestätigung.
5. `merkmale.py` — DSP-Merkmale (Impulsmerkmale wie `Impulsanalyse.kt`, dazu Spektralmerkmale).
6. `yamnet_numpy.py` — NumPy-Nachbau des Interpreters für `yamnet.tflite` (native TFLite-DLLs sind auf dem Owner-PC durch die Windows-Anwendungssteuerung blockiert).
7. `embeddings.py <yamnet.tflite> <WAV-Ordner> <emb.npz>` — YAMNet-Embeddings und Scores (Peak-Normalisierung nur für die Inferenz).
8. `auswerten.py <Kandidaten.csv> <labels.json> <WAV-Ordner> <emb.npz> <Ergebnis.json>` — Ist-Zustand, DSP-Baum, kNN und LogReg mit Leave-one-day-out.

Installation: `python -m venv <Ordner außerhalb des Repos>` und `pip install -r requirements.txt` (Python 3.11).
Ergebnisse und Bewertung: `docs/BERICHT_KI_PRUEFSTAND.md`.

## Stapelanalyse über alle Ereignisse

9. `batch_analyse.py <Drive-Wurzel> <Ausgabeordner> [--min-db 60] [--workers 5]` — rechnet alle Ereignisse ab N dB
   (DSP-Merkmale + YAMNet-Embedding/Scores) parallel, ein Prozess je Stunden-Zip. Fortsetzbar (Shards je Tag und Stunde),
   schreibt `ereignisse.csv` (Metadaten) und `shards/*.npz` (nur Zahlen). Messung: ca. 3,3 Clips/s mit 5 Prozessen.
10. `tabelle.py <Drive-Wurzel> <Ausgabeordner> <labels.json> [--cluster 30]` — `detail.csv` (eine Zeile je Clip, 48 Spalten)
    und `cluster_uebersicht.md` (Gruppen nach Klang mit typischen Clips zum Anhören). Braucht den Drive nicht, wenn `ereignisse.csv` vorhanden ist.

## Weitere Werkzeuge

11. `gruppen_seiten.py` — Hörseiten je Klanggruppe (Artifact-Seiten, WAVs eingebettet).
12. `urteile_auswerten.py` — Auswertung auf den Owner-Urteilen (Test auf direkt gehörten Clips).
13. `batch_alt.py <Schallmessung-Ordner> <Ausgabe>` — ältere Aufnahmen (Zips Juni–August) mit PCE-Pegel; läuft in Durchgängen bis alles fertig und geprüft ist, passt die Prozesszahl alle 10 Minuten an CPU und Speicher an.
14. `tagesprotokoll.py` — Tagesprotokoll als Text und CSV (Zeiträume mit Dichte-Wörtern, Dauer nach dB(A)-Verlauf).

Ausführliche Anleitung, Klassenabbildung und Grenzen: `docs/KI_PRUEFSTAND_ANLEITUNG.md`.
15. `protokoll_vorbereiten.py`, `protokoll_zusammenfuehren.py`, `pruefseite_tage.py` — Eingaben für das Protokoll aus den Hörrunden, Zusammenführung mehrerer Protokolle, gezielte Prüfseite für einzelne Tage.
16. `bericht_lesen.py` — liest den Gesamtbericht (PDF) ein: Tageswerte und Tagesseiten als JSON.
17. `tagebuch_zusammenfuehren.py` — führt das Bautagebuch (xlsx) mit dem Vorschlag zusammen, entfernt Platzhalter und ergänzt ein Blatt mit dem Abgleich gegen den Gesamtbericht.
