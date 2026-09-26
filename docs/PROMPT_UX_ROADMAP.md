# Übersicht: Umsetzungs-Prompts zum UX/UI-Audit

Sieben Arbeitsaufträge, einer je Phase der Roadmap aus `UX_UI_AUDIT.md` (Kapitel 33).
Jeder ist eigenständig lesbar; diese Datei ist nur die Karte.

**Die Spezifikation ist der Audit, nicht dieser Prompt.** Die Prompts legen Zuschnitt,
Reihenfolge, Fallen und Abnahmekriterien fest — was genau zu ändern ist, steht je Finding im
Audit, mit Datei, Zeile, Zielverhalten und Testauswirkung.

## Die sieben Phasen

| Prompt | Inhalt | Findings | Risiko | Passt zu (AGENTS.md §9) |
|---|---|---|---|---|
| [Phase 1](PROMPT_UX_PHASE1.md) | Datenverlust und falsche Aussagen stoppen | F-01 (T1+2), F-06, F-27 | niedrig | Codex oder Antigravity |
| [Phase 2](PROMPT_UX_PHASE2.md) | Sichtbarkeit herstellen | F-04, F-05, F-09, F-11, F-12, **F-35** | niedrig–mittel | Codex oder Antigravity |
| [Phase 3](PROMPT_UX_PHASE3.md) | Berichtsflow entschärfen | F-07, F-08, F-28, F-15 | niedrig | Antigravity |
| [Phase 4](PROMPT_UX_PHASE4.md) | Messvorgang als Klammer, **Room 25 → 26** | F-16 (Weg B), F-01 T3, F-13, F-26 | **hoch** | **eher Codex** – Datenmodell, nicht Layout |
| [Phase 5](PROMPT_UX_PHASE5.md) | Fehlerprävention und Accessibility | F-10, F-21, F-22, F-23, F-31, F-33, **F-34** | niedrig | **Antigravity** – braucht Emulator |
| [Phase 6](PROMPT_UX_PHASE6.md) | Strukturelle Umbauten, einzeln | S-3 (F-02, F-03), S-1, S-4, S-5 (F-18, F-32) | **hoch** | Antigravity, **mit Gerätetest** |
| [Phase 7](PROMPT_UX_PHASE7.md) | Aufräumen | F-25, F-24, F-29, F-17, F-19, F-20 | niedrig | Antigravity |

## Abhängigkeiten

```
Phase 1 ──► Phase 4   (F-01 Teil 3 braucht die DAO-Abfragen aus F-16)
Phase 3 ──► Phase 6   (S-4 setzt den entschärften Berichtsflow voraus)
Phase 5: F-34 ──► F-21  (erst den Titel retten, dann die Badges vergrößern)
Phase 6: S-3 ──► S-1
```

Phase 2 und Phase 7 hängen an nichts und können jederzeit dazwischen.

## Was nicht in den Prompts steht, weil es entschieden ist

- **Reihenfolge „erst messen, dann fotografieren" bleibt.** Ausdrücklich als „nicht
  verhandelbar" dokumentiert (`FotoDokumentationSheet.kt:47-53`); kein Finding rührt daran.
- **F-16 geht Weg B** (`messvorgangId` + Migration 25 → 26), nicht Weg A.
- **F-14 ist erledigt** (PR #203, gemergt) und aus Phase 2 herausgenommen.
- **Keine neuen Werkzeuge** ohne Rückfrage: Kontrastmessung und Screenshot-Vergleich bräuchten
  `espresso-accessibility` bzw. eine Screenshot-Bibliothek — der Owner hat am 25.09.2026
  entschieden, ohne auszukommen.

## Erwarteter Gesamteffekt

Der Standard-Workflow fällt laut Audit (Kapitel 10.4) von **19 auf etwa 7 Taps** — der größte
Teil davon in Phase 6, ein spürbarer Teil schon in Phase 3.

## Eine Warnung, die für alle sieben gilt

Der Audit wurde am 25.09.2026 gegen den damaligen Stand geschrieben. **Jede Zeilenangabe am Code
verifizieren, bevor sie benutzt wird.** Stimmt eine nicht mehr, gilt der Code — und die
Abweichung gehört in den PR, nicht stillschweigend korrigiert.
