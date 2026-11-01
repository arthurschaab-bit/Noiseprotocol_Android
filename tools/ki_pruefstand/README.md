# KI-Prüfstand

Python-Skripte (nur Standardbibliothek bisher) für den Vergleich von Erkennungsansätzen.
Keine WAVs, Kandidatenlisten oder Drive-Pfade im Repo.

1. `auswahl.py <Drive-Wurzel> <Kandidaten.csv>` — Schritt A, Kandidaten aus den Nachtrag-CSVs.
2. `wavs_holen.py <Drive-Wurzel> <Kandidaten.csv> <Zielordner>` — Schritt B, WAVs aus den Stundenzips (Zielordner außerhalb des Repos).
3. `ersatz.py <Drive-Wurzel> <Kandidaten.csv> <wav>...` — ersetzt Kandidaten, deren WAV nicht in den Zips liegt (`PYTHONPATH=. python -s ersatz.py ...`).
4. `bestaetigung_seite.py <Kandidaten.csv> <WAV-Ordner> <Ausgabe.html>` — Schritt C, Seite für die Owner-Bestätigung.
