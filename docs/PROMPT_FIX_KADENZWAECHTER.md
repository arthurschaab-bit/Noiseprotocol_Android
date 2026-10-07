# Prompt: Bugfix — Kadenzwächter misst den falschen Zeitpunkt und trennt gesunde Verbindungen

**Priorität 6, Welle 2.** Befund D aus [`BEFUNDE_BUNDLES_2026-10-07.md`](BEFUNDE_BUNDLES_2026-10-07.md).

**Erst messen, dann ändern.** Der Mechanismus unten ist am Code nachvollzogen, aber nicht gemessen.
Schritt 1 belegt ihn oder widerlegt ihn. Erst danach wird geändert.

**Setzt voraus:** `PROMPT_FIX_VERBINDUNGSFEHLVERSUCHE_MELDEN.md` ist gemergt. Beide ändern
`ConnectionSupervisor.kt`.

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

Lies `AGENTS.md` vollständig, sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann
  `git switch -c fix/kadenzwaechter-messpunkt origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch. Beispiel:
  `fix(meter): Kadenz an der Frame-Ankunft messen statt beim Sammeln`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`
  (keine neuen Befunde in den geänderten Dateien). Nur bei Grün committen und pushen.
- **Nur handgeschriebene Fakes** (`FakeMeterTransport`). Kein Mockito, kein MockK.
- **Sicherheitsrelevanter BLE-Code.** Der Kadenzwächter ist Teil der Stream-Plausibilisierung
  gegen Spoofing (Plan Abschnitt 6). Prüfe die Änderung gegen
  `docs/CHECKLISTE_M6_SICHERHEITSREVIEW.md` Teil 2 und dokumentiere das im PR. **Die Prüfung darf
  nicht schwächer werden**, sie soll nur das Richtige messen.
- **Toleranz und Schwelle nicht ändern.** `cadenceTolerance = 0.5` in `AppContainer.kt:266` ist
  eine Owner-Entscheidung nach dem Gerätetest, `MIN_CADENCE_VIOLATIONS = 2` ebenso. Änderungen nur
  nach Rückfrage.
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.

---

## 1 · Befund

Trennungen wegen Kadenzverletzung (`DEGRADED: Framekadenz …`) gegenüber allen Trennungen:

| Fenster | Kadenz | Datenstillstand | (Wieder-)Verbindungen |
|---|---:|---:|---:|
| P30, 01.10. 14:14 – 05.10. 04:31 UTC | **5** | 1 | 6 |
| Pixel, 23.09. – 07.10. | **9** | 3 | 19 |

Gemessene Abstände gegen das Fenster `[257, 772] ms` (515 ms ± 50 %):
**0, 1, 2, 3, 6, 27, 135, 223, 850 ms**. Abstände von 0–6 ms sind bei einem Gerät mit einem Frame
alle 515 ms unmöglich. Jede dieser Trennungen kostet einen Reconnect und erzeugt ein
`DEGRADED`-Ereignis, also ein rotes Ausfallband. Das senkt die Messintegrität im Bericht.

### Wahrscheinlicher Mechanismus (nachvollzogen, nicht gemessen)

- `cadenceWatcher` in `ConnectionSupervisor.monitorStreamingSession()` sammelt den **StateFlow**
  `transport.lastFrameAt` und nimmt `now.now()` **beim Sammeln** als Ankunftszeit. Der Kommentar
  dort begründet das mit der Steuerbarkeit in Tests.
- Ein StateFlow **fasst zusammen** und lässt gleiche Werte weg. Der Sammler sieht nicht jedes
  Frame, und die Zeit, zu der er drankommt, hängt vom Dispatcher ab, nicht vom Funk.
- `BleMeterTransport.onNotify()` setzt `_lastFrameAt` für **jedes** Frame, das `decoder.feed(raw)`
  aus einer Notification liefert. Mehrere Frames pro Notification sind laut Kommentar dort
  möglich („nach einer Resynchronisation oder bei zusammengefassten Notifications“).
- Zweite mögliche Ursache: `Pce323Profile.FRAME_SIZE = 23`. Bei der Standard-MTU von 23 passen
  nur 20 Byte Nutzlast in eine Notification, ein Frame verteilt sich also auf zwei. Prüfe, ob
  `requestMtu` greift (`BleMeterTransport.onMtuChanged`, optionaler Schritt in `GattQueue`) und
  welche MTU auf dem P30 tatsächlich ausgehandelt wird. Schickt die Funkbrücke des Messgeräts
  feste 20-Byte-Stücke, werden Frames schubweise fertig. Dann ist schon die echte Ankunftszeit
  unregelmäßig, nicht nur die beim Sammeln.
- Das Protokoll ist in `docs/PROTOKOLL_PCE-323.md` beschrieben, lies es vorher.

---

## 2 · Auftrag

### Schritt 1 — Messen

1. **Unit-Test, der den Fehlalarm zeigt:** `FakeMeterTransport` liefert Frames exakt alle 515 ms
   (virtuelle Zeit), aber je zwei Frames mit gleichem `receivedAt` direkt hintereinander, oder
   der Sammler wird künstlich verzögert. Erwartung heute: `BLE_CADENCE_INVALID`. Der Test
   dokumentiert den Ist-Zustand und wird nach Schritt 2 zur Regression.
2. **Messpunkt am Gerät, nur Debug:** Je Notification Länge und Zeit, je fertigem Frame
   `receivedAt`, für die ersten 200 Frames nach dem Verbindungsaufbau. Dazu die ausgehandelte MTU.
   Über `diagnosticLogger` oder Logcat, **nicht** in Release-Builds. Der Owner liefert damit ein
   Bundle. Bis dahin ist Schritt 2 nur auf Basis von Schritt 1.1 möglich.
3. Halte im PR fest, welcher Mechanismus belegt ist: Sammler, Notification-Bündelung oder beides.

### Schritt 2 — Richtig messen

- Den Abstand aus **Frame-Zeitstempeln** berechnen, nicht aus `now.now()` beim Sammeln. Dafür den
  nicht zusammenfassenden `transport.frames` (SharedFlow) statt des `lastFrameAt`-StateFlows
  nutzen.
- **Testbarkeit erhalten:** `receivedAt` kommt heute aus `Instant.now()` in
  `Pce323FrameDecoder.kt:186`. Gib dem Decoder bzw. Transport die injizierbare `InstantSource`,
  wie sie der Supervisor schon hat. `FakeMeterTransport` setzt `receivedAt` bereits aus der
  Testzeit.
- Frames **derselben Notification** (gleiches `receivedAt`, oder Abstand unter einer kleinen
  Schwelle, die du aus der Messung begründest) zählen als **eine** Ankunft. Belegt Schritt 1, dass
  die Funkbrücke Frames schubweise liefert, ist die Prüfgröße der Abstand zwischen Ankünften,
  nicht zwischen Frames. Begründe das im PR.
- Die Spoofing-Erkennung bleibt wirksam. Ein Gerät, das dauerhaft im falschen Takt sendet, wird
  weiter getrennt (Test 3).

### Nicht Teil dieses Auftrags

- Keine Änderung an Toleranz, Schwelle, Fehlerraten- oder Stillstandswächter.
- Kein Umbau des Decoders über die Zeitquelle hinaus.

---

## 3 · Tests (JVM, `FakeMeterTransport`, virtuelle Zeit)

1. Zwei Frames je Notification im 515-ms-Takt → **kein** `BLE_CADENCE_INVALID` mehr. Vorher rot.
2. Verzögerter Sammler, Frames pünktlich → kein Fehlalarm. Vorher rot.
3. Frames dauerhaft alle 150 ms (falscher Takt) → nach `MIN_CADENCE_VIOLATIONS` weiterhin
   `DEGRADED` und Trennung.
4. Frames dauerhaft alle 1.200 ms → ebenso.
5. Die bestehenden Kadenz-Tests bleiben grün oder werden mit Begründung im PR angepasst.

---

## 4 · Akzeptanzkriterien

- [ ] Der Mechanismus ist gemessen und im PR belegt (Schritt 1).
- [ ] Die Kadenz wird an der Ankunft gemessen, nicht beim Sammeln (Tests 1 und 2).
- [ ] Die Spoofing-Erkennung greift weiter (Tests 3 und 4). Die Prüfung gegen
      `CHECKLISTE_M6_SICHERHEITSREVIEW.md` Teil 2 ist im PR dokumentiert.
- [ ] Toleranz und Schwelle unverändert.
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 5 · Gerätecheck (macht der Owner nach dem Merge)

- Einen Tag mit verbundenem PCE-323 aufzeichnen.
- Im nächsten Bundle: `DEGRADED: Framekadenz` kommt höchstens noch vereinzelt vor, nicht mehr
  bei fast jeder Trennung. Vergleichswert: 5 von 6 Trennungen am 01.–05.10.

Trag diese Checks als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein, die
Ergebnis-Spalte bleibt leer.
