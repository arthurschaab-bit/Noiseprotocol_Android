# Prompt: UX-Roadmap Phase 1 — Datenverlust und falsche Aussagen stoppen

Umsetzung von **Phase 1** der Roadmap aus `docs/UX_UI_AUDIT.md` (Kapitel 33). Drei Findings:
[F-01](UX_UI_AUDIT.md) (Teile 1 und 2), **F-06**, **F-27**.

Diese Phase steht bewusst zuerst: Sie ist die einzige, in der heute **Daten verloren gehen** und
in der die App dem Nutzer **falsche Zahlen** anzeigt. Sie ist rein additiv — keine
Schemaänderung, keine Service-Änderung, kein struktureller Umbau.

**Der Audit ist die Spezifikation.** Lies zu jedem Finding den vollständigen Eintrag in
`docs/UX_UI_AUDIT.md`; dort stehen betroffener Code mit Zeilennummern, das Zielverhalten, das
State-Modell nach der Änderung und die erwartete Testauswirkung. Dieser Prompt wiederholt das
nicht, sondern legt Zuschnitt, Reihenfolge und Abnahmekriterien fest.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen; sie gilt unverändert. Insbesondere:

- `git fetch origin`, dann `git switch -c fix/ux-phase1-datenverlust origin/main`.
  **Nie auf `main` pushen.**
- Conventional Commits, Typ englisch, Beschreibung deutsch. Klein geschnitten: ein Commit je
  abgeschlossenem Teilschritt, nicht ein großer am Ende.
- Code-Bezeichner englisch, UI-Texte deutsch. Keine neuen deutschen Literale im Code — neue
  Texte gehören nach `strings.xml` (`values/`, `values-de/`, `values-en/`).
- `./gradlew assembleDebug lintDebug test` muss grün sein, dazu `./gradlew ktlintCheck` **ohne
  neue Befunde in den geänderten Dateien** (die Task ist auf `main` bereits rot, das ist bekannt).
- **Die Kommandoausgabe kommt in den PR**, nicht deren Zusammenfassung.
- **Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben.** Wenn du etwas nicht prüfen
  konntest (kein Gerät, kein Emulator), schreib genau das.
- `fallbackToDestructiveMigration()` ist verboten. **Diese Phase braucht keine Schemaänderung.**
  Wenn du glaubst, doch eine zu brauchen, halte an und frag — das wäre Phase 4.
- Draft-PR gegen `main`, Definition of Done nach `AGENTS.md` §7.

**Verifiziere jede Zeilenangabe am Code, bevor du sie benutzt.** Der Audit wurde am 25.09.2026
erstellt; stimmt eine Angabe nicht mehr, gilt der Code, und die Abweichung gehört in den PR.

---

## 1 · F-01 — Eingaben gehen bei Drehung verloren, und das Sheet kommt nicht zurück

**P0, das einzige im ganzen Audit.** Zwei Fehler in einem: Bis zu 13 Formularfelder gehen
verloren, *und* das Sheet erscheint danach nicht erneut, weil der „schon gefragt"-Merker die
Drehung überlebt, die Eingaben aber nicht.

In dieser Phase werden **nur Teile 1 und 2** umgesetzt:

1. Alle 13 Formularfelder in `GesamtberichtStammdatenSheet` auf `rememberSaveable` umstellen,
   ebenso `notiz` in `FotoDokumentationSheet` und `noteText` in `MarkNoiseEventBottomSheet`.
   Es sind Strings — ein eigener `Saver` ist nicht nötig.
2. `fotoSheetFuerSession` und `stammdatenSheetFuerSession` in `MainActivity` auf
   `rememberSaveable` umstellen (`Long?` ist saveable).

**Teil 3** (den „schon gefragt"-Merker vom Compose-State auf den Datenbestand heben) gehört zu
**Phase 4**, weil er an denselben DAO-Abfragen hängt wie F-16. Fass ihn hier nicht an.

**Falle:** Nach Teil 2 überlebt der Sheet-Zustand die Drehung — aber der Merker
`zuletztGefragteSession` tut das schon heute. Prüfe im Emulator, dass die Kombination aus beidem
kein Sheet *doppelt* öffnet.

### Abnahme F-01

- Drehung mit offenem Stammdaten-Sheet und teilweise ausgefüllten Feldern: **alle** Eingaben
  stehen danach noch da.
- Dasselbe für das Foto-Sheet (`notiz`) und `MarkNoiseEventBottomSheet` (`noteText`).
- Neuer instrumentierter Test mit `ActivityScenario.recreate()` bzw.
  `composeRule.activityRule.scenario.recreate()`, der genau das prüft. Ein Robolectric-Test
  allein genügt hier **nicht** — der Fehler hängt am echten Activity-Neuaufbau (`AGENTS.md` §8b).

---

## 2 · F-06 — Messdauer und Betriebsart hängen an der falschen Session

Das Cockpit rechnet die verstrichene Zeit aus `letzteSession.startedAt`, **ohne zu prüfen, ob
diese Session überhaupt noch offen ist** (`endedAt == null`). Nach dem Ende einer Messung zeigt
die Anzeige deshalb weiter — und zwar eine Dauer, die es nicht gibt.

Der Witz daran: Die korrekte Logik liegt bereits fertig und getestet im Repository.
`leiteDashboardAnzeigeAb` in `messreihe/DashboardStatus.kt` macht genau die richtige
Fallunterscheidung — **hat aber keinen einzigen Aufrufer.**

**Was zu tun ist:** `LiveCockpitCard` auf `leiteDashboardAnzeigeAb` umstellen, statt die Dauer
selbst zu rechnen. Keine neue Logik erfinden. Wenn die vorhandene Funktion nicht ganz passt,
erweitere sie — und erweitere ihre Tests mit.

### Abnahme F-06

- Nach dem Beenden einer Messung zeigt das Cockpit keine laufende Dauer mehr.
- `leiteDashboardAnzeigeAb` hat einen produktiven Aufrufer.
- Die vorhandenen JVM-Tests der Funktion bleiben grün; für jeden neu abgedeckten Fall kommt ein
  Test dazu.
- `MicrophoneCockpitRegressionTest` prüfen — er fasst dieselbe Anzeige an.

---

## 3 · F-27 — Drei Exportwege ohne jede Fehlerbehandlung

CSV-Export, PDF-Export und Teilen laufen ohne `try`/`runCatching`. Schlägt einer fehl, passiert
für den Nutzer **nichts sichtbares** — der Dialog bleibt stehen, als würde noch gearbeitet.

**Was zu tun ist:** `runCatching` um die Export- und Teilen-Aufrufe, Fehlertext über den
vorhandenen `onShowSnackbar`-Kanal, zusätzlich `diagnosticsReporter.report(...)` wie beim
High-End-Bericht.

**Falle:** Bei `BerichtScreen` und `ProtokollScreen` **existiert der Snackbar-Kanal noch nicht**
— er muss erst durchgereicht werden. Das ist F-31 und gehört eigentlich zu Phase 5. Reiche den
Kanal hier nur für diese beiden Screens durch, so weit F-27 ihn braucht; die übrigen vier
Screens bleiben Phase 5. Schreib in den PR, dass du das getan hast.

### Abnahme F-27

- Ein erzwungener Exportfehler (z. B. nicht beschreibbares Ziel) erzeugt eine sichtbare Meldung
  **und** einen Eintrag im Diagnoseprotokoll.
- Kein Pfad verschluckt eine Ausnahme stillschweigend.
- Neue Compose-Tests für den Fehlerfall je Exportweg.

---

## 4 · Reihenfolge

F-01 zuerst (P0), dann F-06, dann F-27. Jeder Block ein eigener Commit. F-06 und F-27 sind
voneinander unabhängig.

---

## 5 · Was ausdrücklich **nicht** Teil des Auftrags ist

- **F-01 Teil 3** (Merker auf Datenbestand heben) → Phase 4.
- **F-31** über die zwei für F-27 nötigen Screens hinaus → Phase 5.
- Jede Schemaänderung, jede Service-Änderung, jeder strukturelle Umbau.
- „Während ich hier bin"-Aufräumen. `AGENTS.md` §5 gilt.

---

## 6 · Definition of Done

1. `assembleDebug`, `lintDebug` und `test` grün — **Ausgabe im PR**.
2. Beide bestehenden Room-Migrationstests weiterhin grün (diese Phase fasst das Schema nicht an;
   der Nachweis gehört trotzdem in den PR).
3. Jedes der drei Findings einzeln adressiert, jeweils mit den oben genannten Abnahmekriterien.
4. Neue Tests wie beschrieben, insbesondere der instrumentierte Drehungs-Test für F-01.
5. Draft-PR gegen `main` mit: was geändert · was verifiziert (Kommando + Ergebnis) · was bewusst
   offen blieb · jede Abweichung vom Audit.
6. Kurzmeldung an den Owner: erledigt / nicht erledigt / aufgefallen.
