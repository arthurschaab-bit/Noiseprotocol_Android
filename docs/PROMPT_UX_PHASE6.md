# Prompt: UX-Roadmap Phase 6 — Strukturelle Umbauten (einzeln, jeweils mit Gerätetest)

Umsetzung von **Phase 6** der Roadmap aus `docs/UX_UI_AUDIT.md` (Kapitel 33). Vier strukturelle
Verbesserungen: **S-3** (umfasst F-02 und F-03), dann **S-1**, **S-4** und **S-5** (umfasst F-18
und F-32).

---

## ⚠️ Vorab drei Warnungen — bitte vor dem ersten Commit lesen

**1. Das ist die Phase mit dem größten Nutzen und dem größten Risiko.** Hier fällt der
Standard-Workflow von **19 auf etwa 7 Taps** (Kapitel 10.4 des Audits). Dafür wird der
Lebenszyklus des Foreground Service angefasst — und das README führt genau diesen Bereich
mehrfach als Quelle von Gerätetest-Befunden.

**2. Jeder der vier Umbauten ist ein eigener Auftrag, eigener Branch, eigener PR.** Nicht alles
in einem. Der Audit sagt das ausdrücklich: „einzeln, jeweils mit Gerätetest". Dieser Prompt
beschreibt alle vier, damit du den Zusammenhang kennst — **abgearbeitet wird einer nach dem
anderen, mit Rückmeldung an den Owner dazwischen.**

**3. Ein Gerätetest nach `docs/CHECKLISTE_GERAETETEST.md` ist zwingend.** Kein Emulator ersetzt
ihn hier. Wenn kein Gerät verfügbar ist, ist der jeweilige Umbau **nicht fertig** — dann sagst du
das, statt ihn als erledigt zu melden.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen. Insbesondere:

- `git fetch origin`, dann je Umbau ein eigener Branch von `origin/main`:
  `feature/ux-s3-autoverbindung`, `feature/ux-s1-messbereitschaft`,
  `feature/ux-s4-berichtsarbeitsplatz`, `feature/ux-s5-filtermodell`.
  **Nie auf `main` pushen.**
- Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten.
- `./gradlew assembleDebug lintDebug test` grün, `ktlintCheck` ohne neue Befunde.
  **Ausgabe in den PR.**
- `./gradlew connectedAndroidTest` zusätzlich, **Ausgabe in den PR**.
- **Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben.**
- Draft-PR je Umbau, Definition of Done nach `AGENTS.md` §7.

---

## 0a · Hinweis zu F-35 — **kein Blocker, gehört nicht hierher**

Eine frühere Fassung dieses Prompts führte [F-35](UX_UI_AUDIT.md) als technischen Blocker für
S-3. **Das war falsch und ist am 26.09.2026 korrigiert worden.**

`berechneForegroundServiceType()` liefert nur dann `0`, wenn weder Mikrofonberechtigung noch ein
gepinntes Messgerät vorliegt. S-3 setzt ein **gepinntes** Gerät voraus — dort greift der Typ
`connectedDevice`, der ohne `RECORD_AUDIO` zulässig ist. **S-3 ist nicht betroffen.**

F-35 ist ein stiller Fehlschlag im Grenzzustand ohne Mikrofon und ohne Gerät und gehört zu
**Phase 2** (`PROMPT_UX_PHASE2.md`), zusammen mit F-09 und F-11.

---

## 1 · S-3 — Automatische Geräteverbindung, getrennt von der Aufzeichnung

Umfasst **F-02** und **F-03**. Das ist der Umbau mit dem größten unmittelbaren Effekt.

**Heute gilt:** „Verbinden" und „Messung starten" sind faktisch dasselbe. Beide starten denselben
Foreground Service, und `AudioRecordingService.kt:343` setzt `_laeuft.value = true` **unabhängig
davon, ob überhaupt aufgezeichnet wird**. Wer nur den Pegel sehen will, startet zwangsläufig eine
Aufzeichnung. Beim App-Start passiert gar nichts — es gibt keinen automatischen Verbindungsaufbau.

Dazu **F-03**: `ConnectionSupervisor.kt:213-221` kehrt nach `FAILED` mit `return@coroutineScope`
zurück und versucht es **nie wieder**. Eine einmal fehlgeschlagene Verbindung bleibt tot, bis der
Nutzer selbst eingreift.

**Owner-Entscheidung vom 25.09.2026:** Schalter „Automatisch mit dem Messgerät verbinden" ist
freigegeben, **Default an**.

**Was zu tun ist:** Verbindung und Aufzeichnung als zwei getrennte Zustände führen. Automatischer
Verbindungsaufbau beim App-Start, wenn der Schalter an ist. `FAILED` wird zu einem Zustand, aus
dem ein erneuter Versuch herausführt.

**Fallen:**
- Der Foreground-Service-Typ wird aus den vorhandenen Berechtigungen berechnet
  (`berechneForegroundServiceType()`) und kann **`0`** liefern, wenn weder Mikrofon noch
  gepinntes Gerät vorliegen. Der typlose Rückfall existiert (`:409`, `:423`), **ist aber in
  dieser Kombination nie beobachtet worden** (Audit Kapitel 35.1). Ein instrumentierter Test
  dafür liegt in PR #204 bereit — prüf, ob er inzwischen gelaufen ist.
- Ein Dienst, der ohne Aufzeichnung im Vordergrund läuft, braucht eine Notification, die das
  ehrlich benennt. „Messung läuft" wäre dann falsch.

---

## 2 · S-1 — Messbereitschaft als eigenes Konzept

Baut auf S-3 auf: Wenn Verbindung und Aufzeichnung getrennt sind, braucht der Nutzer einen
Zustand dazwischen — „bereit zu messen". Erst danach ergibt der reduzierte Workflow Sinn.

**Nicht vor S-3 beginnen.**

---

## 3 · S-4 — Bericht-Tab als Arbeitsplatz statt Zwischenseite

Der Bericht-Tab leitet heute überwiegend weiter, statt selbst etwas zu tun. Nach Phase 3
(Vorprüfung, Gebietseinstufung, Zeitraum) ist der Weg dafür frei.

**Voraussetzung:** Phase 3 muss gemergt sein.

---

## 4 · S-5 — Ein Filtermodell für Aufnahmen und Sessions

Umfasst **F-18** und **F-32**. Es gibt zwei Filtersysteme mit unterschiedlicher Persistenz und
zwei Filter-Buttons nebeneinander.

**Was zu tun ist:** `SessionFilterState` analog persistieren (`SettingsManager`, neue Schlüssel)
und die beiden Buttons zu einem Einstieg zusammenführen; „nur mit Ereignissen" wird ein Chip im
Panel.

**Falle, die der Audit ausdrücklich nennt:** Es gibt einen Test, der die **zwei** Buttons prüft.
Führ die Buttons zusammen und **pass den Test an** — bau nicht die UI um den Test herum.

---

## 5 · Reihenfolge — verbindlich

**S-3 → S-1 → S-4 → S-5.** S-3 und S-1 hängen zusammen. S-4 setzt Phase 3 voraus. S-5 ist
unabhängig und kann vorgezogen werden, wenn S-3 blockiert.

Nach **jedem** Umbau: Gerätetest, PR, Rückmeldung an den Owner. Erst dann der nächste.

---

## 6 · Was ausdrücklich **nicht** Teil des Auftrags ist

- **S-2** („Messvorgang" als Klammer) — das ist Phase 4 und braucht die Room-Migration 25 → 26.
- **S-6** (Kontextregel für Foto- und Stammdaten-Abfrage) — hängt an S-2.
- Die Reihenfolge „erst messen, dann fotografieren" ändern. Sie ist als „nicht verhandelbar"
  dokumentiert (`FotoDokumentationSheet.kt:47-53`).
- Mehr als einen der vier Umbauten in einem PR.

---

## 7 · Definition of Done — je Umbau

1. `assembleDebug`, `lintDebug`, `test` grün — **Ausgabe im PR**.
2. `connectedAndroidTest` gelaufen — **Ausgabe im PR**.
3. **Gerätetest nach `docs/CHECKLISTE_GERAETETEST.md` durchgeführt und protokolliert.** Ohne
   ihn ist der Umbau nicht fertig; wenn kein Gerät verfügbar war, steht genau das im PR.
4. Bei S-3: belegt, dass „nur verbinden" ohne Aufzeichnung funktioniert und dass ein
   fehlgeschlagener Verbindungsversuch wieder aufgenommen wird.
5. Tapzahl des Standard-Workflows vorher/nachher im PR belegt.
6. Draft-PR gegen `main`, Kurzmeldung an den Owner, dann Stopp bis zur Freigabe des nächsten
   Umbaus.
