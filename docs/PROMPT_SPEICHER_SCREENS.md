# Prompt: Untersuchung — Wächst der Heap beim Öffnen von Screens?

**Priorität 4. Erst beginnen, wenn `PROMPT_FIX_OOM_DRIVE_SYNC.md` gemergt ist** und der Owner
danach ein neues Support-Bundle vom Huawei P30 geliefert hat. Belege:
[`BEFUNDE_P30_2026-09-23.md`](BEFUNDE_P30_2026-09-23.md), Abschnitt 4.

Das ist ein **Untersuchungsauftrag**. Ergebnis ist ein Bericht mit Messwerten und einem Vorschlag,
**keine Codeänderung**. Umgesetzt wird erst nach Freigabe durch den Owner (AGENTS.md §8a).

---

## 0 · Arbeitsregeln

- `AGENTS.md` vollständig lesen.
- Vor dem Start `git fetch origin`. Gearbeitet wird auf `origin/main`, **ohne Branch und ohne
  Commit**, außer für den Bericht selbst (siehe unten).
- Nie behaupten, etwas sei die Ursache, ohne es gemessen oder im Code belegt zu haben. Vermutungen
  als solche kennzeichnen.

---

## 1 · Der Befund

Logcat aus `2026-09-23_164324_manuell.zip`: Die App kommt um 16:43:09 in den Vordergrund, der
Owner tippt sich durch die Screens. Die GC-Zeilen zeigen den belegten Heap:

```
16:43:09.844 … 0% free, 70MB/70MB
16:43:13.361 … 5% free, 112MB/119MB
16:43:17.399 … 0% free, 157MB/157MB
16:43:19.661 … 0% free, 185MB/185MB
16:43:21.454 … 0% free, 216MB/216MB
16:43:23.400 … 0% free, 240MB/240MB
16:43:23.842 … 8% free, 245MB/269MB
```

- In den 15 s steigt der Heap von 70 auf 269 MB; `runtime.json` zeigt danach `heapUsedBytes`
  202.339.688 bei einer Grenze von 402.653.184.
- **Gleichzeitig läuft ein Drive-Nachhollauf:** „Nachholen von 2026-09-20 fehlgeschlagen: Job was
  cancelled“ um 16:43:12 und 16:43:23.
- Vermutung, **nicht belegt**: Der Anstieg kommt vom Nachholen (Befund A1) und nicht von den
  Screens.

---

## 2 · Auftrag

### Schritt 1 — Prüfen, ob es nach dem Sync-Fix noch auftritt

Im neuen Bundle (nach dem Merge von `PROMPT_FIX_OOM_DRIVE_SYNC.md`):
- `log/logcat.txt` nach GC-Zeilen (`… free, XMB/YMB`) durchsuchen, während die App im
  Vordergrund ist.
- Mit dem Owner absprechen, welche Screens er dabei geöffnet hat.

**Bleibt der Heap beim Durchklicken unter etwa 120 MB:** Bericht „erledigt durch den Sync-Fix“,
fertig.

### Schritt 2 — Nur falls der Anstieg bleibt: Bestandsaufnahme im Code

Alle DAO-Methoden, die **unbegrenzte** Listen aus großen Tabellen liefern, und ihre Aufrufer in UI
und ViewModels auflisten. Tabellengrößen auf dem Owner-Gerät laut `db_stats.json`:

| Tabelle | Zeilen |
|---|---:|
| `level_samples` | 8.635.578 |
| `measurements` | 575.403 |
| `diagnostic_log_entries` | 10.427 |
| `noise_records` | 7.954 |

Gesucht sind `suspend fun …(): List<…>` und `fun …(): Flow<List<…>>` ohne `LIMIT` bzw. ohne
Zeitraum. Für jede Fundstelle:
- DAO-Methode und Tabelle,
- welcher Screen bzw. welche Komponente sie beim Öffnen sammelt,
- grobe Zeilenzahl auf dem Owner-Gerät.

### Schritt 3 — Bericht

- Den Bericht als `docs/BEFUNDE_SPEICHER_SCREENS.md` schreiben.
- Branch `fix/speicher-screens-bericht` von `origin/main`, Conventional Commit
  `docs(diagnose): …`, Draft-PR.
- Inhalt: Messwerte aus Schritt 1, Tabelle aus Schritt 2, und je Fundstelle ein Vorschlag
  (Paging, `LIMIT`, Aggregat-Abfrage, Laden erst bei Bedarf).
- **Keine Codeänderung** in diesem PR.

## Akzeptanzkriterien

- [ ] Klare Aussage mit Messwerten, ob der Anstieg nach dem Sync-Fix noch auftritt.
- [ ] Falls ja: vollständige Liste der unbegrenzten Abfragen auf großen Tabellen mit Aufrufern.
- [ ] Vorschläge zur Freigabe durch den Owner; nichts ohne Freigabe umgesetzt.
