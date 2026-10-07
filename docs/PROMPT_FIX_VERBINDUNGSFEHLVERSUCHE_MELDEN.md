# Prompt: Bugfix — Gescheiterte Verbindungsaufbauten richtig benennen und als Fehlercode melden

**Priorität 4, Welle 1.** Befund C aus [`BEFUNDE_BUNDLES_2026-10-07.md`](BEFUNDE_BUNDLES_2026-10-07.md).

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

Lies `AGENTS.md` vollständig, sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann
  `git switch -c fix/verbindungsfehlversuche-melden origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch. Beispiel:
  `fix(meter): gescheiterten Verbindungsaufbau von fehlendem ersten Frame unterscheiden`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`
  (keine neuen Befunde in den geänderten Dateien). Nur bei Grün committen und pushen.
- **Nur handgeschriebene Fakes** (`FakeMeterTransport`). Kein Mockito, kein MockK.
- **Keine Schemaänderung.**
- **Sicherheitsrelevanter BLE-Code:** Änderungen an `ConnectionSupervisor.kt` werden gegen
  `docs/CHECKLISTE_M6_SICHERHEITSREVIEW.md` Teil 2 geprüft. Dieser Auftrag ändert **keine**
  Plausibilisierungslogik (Kadenz, Fehlerrate, Stillstand, Pinning). Halte das im PR fest.
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.
- `PROMPT_FIX_KADENZWAECHTER.md` ändert dieselbe Datei und kommt **nach** diesem Auftrag.

---

## 1 · Befund

- Bundle vom 05.10.2026 (P30): Vom 02.10. 11:51 bis 04.10. 06:39 UTC steht 244 × `Kein Frame
  innerhalb von 5000ms nach Verbindungsaufbau - Versuch verworfen` im Protokoll. Trotzdem meldet
  `health_metrics.json` `"reconnectCount":0` und `"fehlerJeCode":{}`.
- **Die Meldung ist irreführend.** `ConnectionSupervisor.attemptOnce()` wartet nach
  `transport.connect(device)` bis zu `staleAfter` auf `STREAMING`, `FAILED` oder `DISCONNECTED`.
  Alles außer `STREAMING` landet im selben Zweig mit demselben Text. Meldet der Transport sofort
  `FAILED`, weil das Messgerät aus ist (so am 02.–04.10., Owner), steht trotzdem „nach
  Verbindungsaufbau“ im Protokoll.
- In diesem Zweig gibt es nur `diagnosticLogger?.protokolliere(…)`: keinen `DiagnosticCode`, also
  keinen Beitrag zu `fehlerJeCode`. `HealthMetrics.istUnveraendert()` hält ein Gerät, das nie
  verbindet, deshalb für unauffällig. Ein Bundle entsteht nur, weil `diagnosticLogEntryCount`
  wächst.
- `DiagnosticCode.BLE_CONNECT_FAILED` existiert, wird aber nirgends gemeldet. Prüfe das mit
  `grep -rn BLE_CONNECT_FAILED app/src/main`.
- **Gewollt und nicht zu ändern:** `reconnectCount` zählt nur `ConnectionEvent`s.
  `MeasurementRecorder.onState` schreibt vor dem ersten `STREAMING` bewusst keine („Ausfälle einer
  Verbindung, die nie zustande kam, gehören zu keinem Messvorgang“). **Erzeuge keine
  `ConnectionEvent`s**, das würde Ausfallbänder und Datenverfügbarkeit verfälschen.

---

## 2 · Auftrag

### Schritt 1 — Die zwei Fälle trennen

In `attemptOnce()`:
- **`reached == null`:** verbunden, aber innerhalb von `staleAfter` kein Frame. Text wie bisher:
  „Kein Frame innerhalb von …ms nach Verbindungsaufbau“.
- **`reached == FAILED` oder `DISCONNECTED`:** Der Verbindungsaufbau ist gescheitert. Neuer Text,
  z. B. „Verbindungsaufbau gescheitert (FAILED)“.

Das Ergebnis bleibt in beiden Fällen `AttemptOutcome.NEVER_STREAMED`. Backoff, `maxAttempts` und
FAILED-Wartezeit ändern sich nicht. Unterscheide intern den Grund, z. B. über ein eigenes Feld
oder einen Zähler je Grund, damit Schritt 2 ihn kennt. Der Ausnahme-Zweig in `supervise()`
(„Verbindungsversuch gescheitert: …“) zählt als gescheiterter Aufbau.

### Schritt 2 — Als Fehlercode melden

- Gescheiterter Aufbau, auch über den Ausnahme-Zweig → `BLE_CONNECT_FAILED`.
- Verbunden ohne erstes Frame → **neuer Code `BLE_NO_FIRST_FRAME`** (Owner-Entscheidung E1). Er
  steht in `DiagnosticCode.kt` neben `BLE_STREAM_STALLED` und hat eine KDoc wie die Nachbarn.
- **Einmal je gescheiterter Runde** melden (Owner-Entscheidung E2), also an der Stelle, an der
  `supervise()` nach `maxAttempts` `FAILED` setzt:
  - je Grund, der in dieser Runde vorkam, eine Meldung, mit der Anzahl in den Details;
  - danach die Zähler zurücksetzen;
  - folgt vor `maxAttempts` doch noch ein Erfolg, wird nichts gemeldet. Die Textzeilen der
    Einzelversuche stehen dann trotzdem im Protokoll.
- Severity `WARN`, `component = "ConnectionSupervisor"`. Die Details enthalten Anzahl und Grund,
  aber keine Geräteadresse (Redaktion wie bei den bestehenden BLE-Meldungen).

### Nicht Teil dieses Auftrags

- Keine `ConnectionEvent`s, keine Änderung an `reconnectCount` oder `MeasurementRecorder`.
- Keine Änderung an Kadenz-, Fehlerraten- oder Stillstandswächter.

---

## 3 · Tests (JVM, `FakeMeterTransport`, virtuelle Zeit)

Vorhandene Supervisor-Tests suchen (`grep -rl ConnectionSupervisor app/src/test`) und das Muster
übernehmen.

1. Der Transport geht nach `connect` jedes Mal sofort auf `FAILED`, bis `maxAttempts` erreicht
   ist:
   - Text „Verbindungsaufbau gescheitert“;
   - **nicht** „Kein Frame“;
   - genau **eine** Meldung `BLE_CONNECT_FAILED` mit Anzahl `maxAttempts`.
2. Der Transport bleibt in `SUBSCRIBING`, kein Frame, über eine ganze Runde: Text „Kein Frame …“
   und genau eine Meldung `BLE_NO_FIRST_FRAME`.
3. Eine Ausnahme aus `transport.connect` zählt in der Runde als gescheiterter Aufbau und landet
   in der Meldung `BLE_CONNECT_FAILED`.
4. Gemischte Runde (teils Aufbau gescheitert, teils kein Frame) → je eine Meldung pro Grund mit
   den richtigen Anzahlen. Zweite Runde → wieder nur je eine.
5. Drei Fehlversuche, dann Erfolg → keine Meldung.
6. Backoff-Folge und `FAILED` nach `maxAttempts` sind unverändert. Die bestehenden Tests bleiben
   grün.
7. `berechneHealthMetrics` mit solchen Ereignissen → `fehlerJeCode[BLE_CONNECT_FAILED] > 0` und
   `istUnveraendert() == false`.

Die Tests 1, 2, 3 und 7 müssen ohne deine Änderung rot sein. Zeig das im PR.

---

## 4 · Akzeptanzkriterien

- [ ] Ein Gerät, das nie verbindet, erscheint in `fehlerJeCode` jedes Gesundheits-Bundles
      (Test 7).
- [ ] Die Logmeldung sagt, was passiert ist: Aufbau gescheitert oder kein erstes Frame
      (Tests 1, 2).
- [ ] Höchstens eine Meldung je Grund und gescheiterter Runde (Tests 4, 5).
- [ ] Kein `ConnectionEvent` für nie zustande gekommene Verbindungen. Belege das mit einem `grep`
      im PR.
- [ ] Prüfung gegen `CHECKLISTE_M6_SICHERHEITSREVIEW.md` Teil 2 im PR dokumentiert.
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 5 · Owner-Entscheidungen (07.10.2026, „Empfehlungen freigeben“)

- **E1 — Für „verbunden, aber kein erstes Frame“ gibt es den neuen Code `BLE_NO_FIRST_FRAME`.**
  `BLE_STREAM_STALLED` wird nicht mitbenutzt. Stillstand heißt „lief und hörte auf“. Hier lief nie
  etwas, und die Fehlerbilder unterscheiden sich, etwa bei einem falsch gepinnten Gerät (siehe
  Kommentar in `MeterScreen.kt` zum Bose-Lautsprecher).
- **E2 — Einmal je gescheiterter Runde melden**, also wenn nach `maxAttempts` `FAILED` gesetzt
  wird, mit der Anzahl je Grund in den Details. Nicht bei jedem Versuch: Am 02.–04.10. wären das
  244 Meldungen gewesen. Die Einzelversuche stehen ohnehin als Textzeilen im Protokoll, und die
  Kennzahl braucht nur „> 0“.

## 6 · Gerätecheck (macht der Owner nach dem Merge)

1. Messgerät ausschalten, Aufzeichnung laufen lassen, bis `FAILED` erscheint (etwa 3 min).
2. Im Diagnose-Screen steht „Verbindungsaufbau gescheitert“, nicht „Kein Frame … nach
   Verbindungsaufbau“.
3. Im nächsten Gesundheits-Bundle zeigt `health_metrics.json` unter `fehlerJeCode` einen Eintrag
   `BLE_CONNECT_FAILED`.

Trag diese Checks als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein, die
Ergebnis-Spalte bleibt leer.
