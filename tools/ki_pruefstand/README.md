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
