# Prompt: UX-Roadmap Phase 4 — Wiederkehrende Abfragen entschärfen (Room-Migration 25 → 26)

Umsetzung von **Phase 4** der Roadmap aus `docs/UX_UI_AUDIT.md` (Kapitel 33). Vier Findings:
**F-16** (Weg B), **F-01 Teil 3**, **F-13**, **F-26**.

---

## ⚠️ Vorab zwei Warnungen — bitte vor dem ersten Commit lesen

**1. Das ist die einzige Phase mit Schemaänderung.** Der Audit stuft sie als **hohes Risiko**
ein, und zwar ausdrücklich nicht wegen der fachlichen Regeln — die sind entschieden — sondern
allein wegen der Migration. Room-Schemaversion 25 → 26.

**2. Diese Phase passt am schlechtesten zu einem UI-fokussierten Agenten.** `AGENTS.md` §9
ordnet UI-lastige Arbeit mit Emulator-Rückmeldung Antigravity zu, gut spezifizierte,
hardwarefreie Milestones dagegen Codex. Der Schwerpunkt hier ist Datenmodell und Migration, nicht
Layout. **Wenn du dieser Prompt als Gemini/Antigravity bearbeitest, kläre das vorher mit dem
Owner** — es kann die richtige Entscheidung sein, diese Phase abzugeben.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen. Zusätzlich zu den üblichen Regeln gilt hier verschärft:

- `git fetch origin`, dann `git switch -c feature/ux-phase4-messvorgang origin/main`.
  **Nie auf `main` pushen.**
- **`fallbackToDestructiveMigration()` ist verboten.** Ohne Ausnahme. Wer es einsetzt, löscht die
  Messdaten der Nutzer — und das sind Beweismittel.
- **Tabellennamen, Spaltennamen und `identityHash` dürfen sich für alles Bestehende nicht
  ändern.** Die Migrationstests sind der Nachweis.
- **Ein `AppDatabaseV26MigrationTest` ist Pflicht**, gebaut analog zu den 21 vorhandenen
  (`AppDatabaseV4MigrationTest` … `AppDatabaseV25MigrationTest`). Kein PR ohne ihn.
- Das exportierte Schema unter `app/schemas/` wird von KSP erzeugt — **nicht von Hand
  bearbeiten**, sondern durch einen Build erzeugen lassen und mitcommitten.
- `./gradlew assembleDebug lintDebug test` grün, `ktlintCheck` ohne neue Befunde.
  **Ausgabe in den PR.**
- **Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben.**
- Draft-PR gegen `main`, Definition of Done nach `AGENTS.md` §7.

---

## 1 · F-16 — Beide Mess-Sheets erscheinen zweimal je Messvorgang

**Die Ursache steht fest und ist im Audit belegt:** `MeasurementRecorder.kt:296-316` schließt die
Mikrofon-Session und legt eine **neue Session-Zeile** an, wenn die Messquelle wechselt. Für die
Datenbank sind das zwei Sessions — für den Nutzer ist es eine Messung. Beide Sheets hängen an der
Session-ID und erscheinen deshalb zweimal.

**Owner-Entscheidung vom 25.09.2026: Weg B.** Ein `messvorgangId` als Klammer über beide
Sessions, mit Room-Migration 25 → 26. Weg A (Tagesregel ohne Schemaänderung) wurde geprüft und
verworfen.

**Was zu tun ist:**

1. `messvorgangId` in `SessionEntity` aufnehmen.
2. Migration 25 → 26 schreiben. Bestandsdaten brauchen einen sinnvollen Wert — der naheliegende
   Weg ist, jede vorhandene Session ihren eigenen Messvorgang bilden zu lassen (`messvorgangId`
   = eigene ID). Das ist verlustfrei und ändert für Bestandsdaten nichts am Verhalten.
3. `MeasurementRecorder` vergibt beim Quellenwechsel dieselbe `messvorgangId` an die Folgesession.
4. Beide Sheet-Auslöser in `MainActivity` auf `messvorgangId` statt Session-ID umstellen.
5. `DokumentationsFotoDao` und `StammdatenVerlaufDao` bekommen Abfragen „für diesen Messvorgang".

**Falle:** Schritt 2 ist der gefährliche. Eine Migration, die auf dem Entwicklungsrechner
durchläuft, aber den `identityHash` verschiebt, fällt erst beim Nutzer auf. Der
Migrationstest ist keine Formalie.

---

## 2 · F-01 Teil 3 — Merker vom Compose-State auf den Datenbestand heben

Die Teile 1 und 2 von F-01 sind in **Phase 1** erledigt (`rememberSaveable`). Teil 3 gehört
hierher, weil er dieselben DAO-Abfragen braucht wie F-16.

**Was zu tun ist:** `zuletztGefragteSession` und `zuletztGefragteStammdatenSession` entfallen.
An ihre Stelle tritt eine abgeleitete Bedingung auf dem tatsächlichen Datenbestand:

```
sheetSichtbar = offenerMessvorgang != null && !hatXFuerMessvorgang
```

Für Fotos existiert die Abfrage bereits (`dokumentationsFotoDao().fuerSession(id).isEmpty()`) und
wird auf den Messvorgang umgestellt; für Stammdaten kommt sie neu dazu.

**Wirkung:** Das Sheet kehrt nach einer Drehung von selbst zurück, ohne Merker — und es
erscheint genau einmal je Messvorgang statt zweimal.

---

## 3 · F-13 — Drive-Status nicht im Hauptfluss, kein Retry je Datei

Schlägt der Upload einer einzelnen Datei fehl, gibt es keinen Weg, genau diese Datei erneut zu
versuchen. Der Nutzer sieht das Problem außerdem nur, wenn er den Drive-Screen aufsucht.

**Was zu tun ist:** Fehlgeschlagene Dateien je Datei erneut anstoßen können, und den
Sync-Zustand dort sichtbar machen, wo gearbeitet wird.

**Falle:** Drive-Sync ist per Default **aus**. Alles, was du hier baust, muss den Fall „nie
eingerichtet" sauber behandeln und darf ihn nicht wie einen Fehler aussehen lassen.

---

## 4 · F-26 — Batch-Klassifizierung ohne Fortschritt und ohne Doppelklickschutz

**Was zu tun ist:** Ein `var batchLaeuft by remember { mutableStateOf(false) }`, die Menüeinträge
währenddessen deaktiviert, und ein Fortschritt „n von m". Die Schleife kennt die Kandidatenzahl
bereits; ein optionaler `onFortschritt`-Callback in `klassifiziereUndSpeichere` genügt.

Das ist der kleinste Teil dieser Phase und von den übrigen dreien unabhängig.

---

## 5 · Reihenfolge — diese ist verbindlich

1. **F-26 zuerst.** Unabhängig, klein, risikofrei. Bringt einen grünen Commit, bevor es
   gefährlich wird.
2. **F-16 Schritt 1 + 2**: Entity und Migration, *mit* `AppDatabaseV26MigrationTest`.
   **Eigener Commit, eigener grüner Testlauf.** Erst weitermachen, wenn er steht.
3. **F-16 Schritt 3–5**: Recorder, Sheet-Auslöser, DAO-Abfragen.
4. **F-01 Teil 3.** Baut auf den DAO-Abfragen aus Schritt 3 auf.
5. **F-13.** Unabhängig, kann auch vorgezogen werden.

---

## 6 · Was ausdrücklich **nicht** Teil des Auftrags ist

- Die Reihenfolge „erst messen, dann fotografieren" ändern. Sie ist in
  `FotoDokumentationSheet.kt:47-53` ausdrücklich als „nicht verhandelbar" dokumentiert, und der
  Audit bestätigt: **kein Finding rührt daran.**
- Weg A für F-16. Der Owner hat Weg B entschieden.
- Struktureller Umbau von Service oder Verbindungsaufbau → Phase 6.
- Jede weitere Schemaänderung über 25 → 26 hinaus.

---

## 7 · Definition of Done

1. `assembleDebug`, `lintDebug`, `test` grün — **Ausgabe im PR**.
2. **`AppDatabaseV26MigrationTest` vorhanden und grün**, dazu alle 21 bestehenden
   Migrationstests weiterhin grün. Die Ausgabe dieses Laufs gehört vollständig in den PR.
3. Das von KSP erzeugte Schema `app/schemas/…/26.json` ist mitcommittet.
4. Im PR belegt: beide Sheets erscheinen einmal je Messvorgang statt zweimal.
5. Draft-PR gegen `main`: was geändert · was verifiziert (Kommando + Ergebnis) · was offen blieb ·
   jede Abweichung vom Audit.
6. Kurzmeldung an den Owner. **Wenn die Migration Fragen aufgeworfen hat, die du selbst
   entschieden hast: nenn sie ausdrücklich.**
