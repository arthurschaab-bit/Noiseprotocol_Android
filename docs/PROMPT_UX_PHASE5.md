# Prompt: UX-Roadmap Phase 5 — Fehlerprävention und Accessibility

Umsetzung von **Phase 5** der Roadmap aus `docs/UX_UI_AUDIT.md` (Kapitel 33). Sieben Findings:
**F-10**, **F-21**, **F-22**, **F-23**, **F-31**, **F-33** und **F-34**.

**Das ist die Phase, die am besten zu einem UI-Agenten mit Emulator passt** (`AGENTS.md` §9).
Fast alles hier ist Layout, Semantik und Bedienbarkeit — und zwei der Findings lassen sich nur
am laufenden Gerät wirklich beurteilen.

Das Risiko ist niedrig, die Änderungen sind überwiegend additiv.

**Der Audit ist die Spezifikation.** Lies zu jedem Finding den vollständigen Eintrag.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen. Insbesondere:

- `git fetch origin`, dann `git switch -c feature/ux-phase5-fehlerpraevention origin/main`.
  **Nie auf `main` pushen.**
- Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten.
- Code-Bezeichner englisch, UI-Texte deutsch; neue Texte nach `strings.xml` in allen drei
  Ressourcenordnern.
- `./gradlew assembleDebug lintDebug test` grün, `ktlintCheck` ohne neue Befunde in den
  geänderten Dateien. **Ausgabe in den PR.**
- **Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben.**
- Keine Schemaänderung.
- Draft-PR gegen `main`, Definition of Done nach `AGENTS.md` §7.

---

## 1 · F-34 — Der Cockpit-Titel verschwindet · **P1, das Wichtigste hier**

**Dieses Finding ist bereits am Emulator gemessen**, nicht nur vermutet. Die Zahlen stehen im
Audit unter [F-34](UX_UI_AUDIT.md).

`LiveCockpitCard.kt:223` ist eine `Row` mit `Arrangement.SpaceBetween`. Die Titelspalte hat
`Modifier.weight(1f, fill = false)` und `maxLines = 1` (`:234`, `:240`); rechts stehen die
Status-Badges, die mit der Schriftgröße mitwachsen. `weight(1f, fill = false)` teilt der Spalte
nur zu, was die Badges übriglassen — und das war in der Messung **null**:

```
Schriftfaktor 1,0:  Cockpit-Titel: breite=0px maxBreite=0px ueberlaufHoehe=true
Schriftfaktor 1,3 und 2,0:  assertIsDisplayed() auf dem Titel schlägt fehl
```

Der Titel wird also **nicht gekürzt, sondern gar nicht dargestellt** — und schon bei
Standardschrift auf schmalem Gerät.

**Was zu tun ist:** Die Titelspalte darf nicht auf null schrumpfen können. Zwei Wege:
- Mindestbreite über `Modifier.widthIn(min = …)` zusammen mit `weight(1f)` **ohne**
  `fill = false`, oder
- die Kopfzeile bei knapper Breite umbrechen lassen (`FlowRow`), statt Titel und Badges in einer
  Zeile zu erzwingen.

Der zweite Weg ist robuster. In beiden Fällen gehören die Badges auf eine Obergrenze begrenzt,
die dem Titel Platz lässt.

**Der Test liegt bereits vor.** `SchriftskalierungInstrumentedTest` misst genau das. Er sichert
den Titel derzeit **bewusst nicht** zu, sondern prüft nur dessen Existenz — weil eine harte
Zusicherung die CI rot färben würde, solange dieses Finding offen ist. An drei Stellen im Test
steht im Klartext, was nach der Umsetzung dort hingehört:

- `pruefeNichtAbgeschnitten("Cockpit-Titel", …)` bei Schriftfaktor 1,0
- `assertIsDisplayed()` statt `assertExists()` bei 1,3 und 2,0

**Diese drei Stellen scharfzuschalten ist Teil des Auftrags, nicht optional.** Die Messfunktion
ist vorhanden.

---

## 2 · F-21 — Touch-Targets unter 48 dp

Nicht mit F-34 verwechseln: F-21 betrifft die **Größe der klickbaren Fläche**, nicht den Text.
Fünf `IconButton`s liegen unter 48 dp (20/36/36/36/40 dp). Kritischer sind die beiden
Status-Badges in der TopAppBar — sie sind die **primäre** Bedienung für Bluetooth-Verbindung und
WAV-Aufzeichnung, bestehen aber nur aus einem `labelSmall`-Text mit 5 dp vertikalem Padding.

**Was zu tun ist:** `Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)`, bei den Badges
`Modifier.minimumInteractiveComponentSize()` (Material 3) — **ohne die sichtbare Größe zu
ändern**. Zusätzlich `Role.Button` in den Semantics.

**Falle:** F-21 und F-34 betreffen dieselbe Kopfzeile. Wer die Badges vergrößert, nimmt dem
Titel weiter Platz weg. **Setz F-34 zuerst um**, sonst verschlimmerst du es.

**Neuer Test:** Touch-Bounds gegen 48 dp prüfen, über
`SemanticsNodeInteraction.getBoundsInRoot()`.

---

## 3 · F-10 — Kein Speicherplatz-Check vor dem Start einer Messung

Eine Messung kann still an vollem Speicher scheitern. Der Audit führt „Verhalten bei vollem
Speicher" zusätzlich als ungeprüft (Kapitel 35.1) — das heißt: **Prüf am Emulator, was heute
tatsächlich passiert**, bevor du etwas baust.

**Was zu tun ist:** Eine Prüffunktion vor dem Messstart, mit verständlicher Meldung statt eines
stillen Fehlschlags.

**Falle:** Der Schwellenwert ist eine fachliche Aussage (wie lange soll eine Messung noch
laufen können?). Wenn der Audit ihn nicht festlegt, **frag den Owner**, statt eine Zahl zu
erfinden (`AGENTS.md` §8a).

---

## 4 · F-22, F-23, F-31, F-33 — die kleineren vier

- **F-22:** Die Trigger-Quelle wird über ein `Surface` mit Text und kleinem Pfeil geändert — das
  sieht nicht nach Bedienelement aus. Als `AssistChip`/`FilterChip` mit Trailing-Icon
  darstellen, `Role.Button`, 48 dp. **Den WAV-Schalter aus diesem Menü herausnehmen** — er
  gehört zum Mikrofon-Badge, wo er schon existiert (`MicrophoneStatusBadge.kt:104`).
- **F-23:** Der Foto-Knopf ist deaktiviert, sobald das Maximum je Kategorie erreicht ist
  (`FotoDokumentationSheet.kt:173`) — ohne jede Begründung. Begründung ergänzen.
- **F-31:** Sechs Screens haben keinen Snackbar-Kanal. Kanal überall durchreichen, einheitliche
  Signatur. *Hinweis:* Für zwei dieser Screens wurde er in Phase 1 bereits durchgereicht (F-27) —
  prüf den Stand, statt es doppelt zu tun.
- **F-33:** Zwölf `Toast`-Aufrufe neben dem Snackbar-Kanal. Neun sind bereits als
  `onShowSnackbar?.invoke(msg) ?: Toast…` geschrieben und greifen nach F-31 von selbst; die drei
  unbedingten umstellen.

F-31 ist die Voraussetzung für F-33 — in dieser Reihenfolge.

---

## 5 · Reihenfolge

1. **F-34** (P1, und Voraussetzung dafür, dass F-21 nichts verschlimmert)
2. **F-21**, danach die drei scharfgeschalteten Zusicherungen aus F-34 erneut laufen lassen
3. **F-31**, dann **F-33**
4. **F-22**, **F-23**
5. **F-10** zuletzt — er braucht vermutlich eine Owner-Rückfrage zum Schwellenwert

---

## 6 · Was ausdrücklich **nicht** Teil des Auftrags ist

- **F-19** (28 Farbliterale) und **F-20** (156 nicht lokalisierte Literale) → Phase 7. F-20
  bricht viele Compose-Tests, die auf deutschen Text matchen; das gehört nicht in diese Phase.
- Kontrastmessungen und Screenshot-Vergleiche. Beide brauchen neue Abhängigkeiten
  (`espresso-accessibility`, eine Screenshot-Bibliothek), und der Owner hat am 25.09.2026
  entschieden: **nur ohne neue Werkzeuge**. Wenn du sie für nötig hältst, frag — entscheide es
  nicht selbst.
- TalkBack-Fokusreihenfolge. Nicht automatisierbar, gehört in den Gerätetest.

---

## 7 · Definition of Done

1. `assembleDebug`, `lintDebug`, `test` grün — **Ausgabe im PR**.
2. `connectedAndroidTest` gelaufen, **Ausgabe im PR** — diese Phase ist die, bei der das wirklich
   zählt. Wenn kein Emulator verfügbar war: genau das schreiben, nicht umschreiben.
3. Die drei in `SchriftskalierungInstrumentedTest` markierten Stellen sind scharfgeschaltet und
   grün.
4. Neuer Touch-Bounds-Test für F-21.
5. Draft-PR gegen `main`: was geändert · was verifiziert (Kommando + Ergebnis) · was offen blieb ·
   jede Abweichung vom Audit.
6. Kurzmeldung an den Owner.
