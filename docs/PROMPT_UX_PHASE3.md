# Prompt: UX-Roadmap Phase 3 — Berichtsflow entschärfen

Umsetzung von **Phase 3** der Roadmap aus `docs/UX_UI_AUDIT.md` (Kapitel 33). Vier Findings:
**F-07**, **F-08**, **F-28**, **F-15**.

Diese Phase beseitigt den **häufigsten Fehlerpfad der ganzen App**. Der Audit hat ihn
ausgezählt: Wer den Bericht mit leerer Gebietseinstufung erzeugen will, braucht im Fehlerfall
**32 statt 19 Taps** — und erfährt den Grund erst, nachdem er auf „Erstellen" getippt hat.

Erwarteter Effekt nach dieser Phase: −13 Taps im Fehlerfall, −4 Taps im Normalfall.

**Der Audit ist die Spezifikation.** Lies zu jedem Finding den vollständigen Eintrag.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen. Insbesondere:

- `git fetch origin`, dann `git switch -c feature/ux-phase3-berichtsflow origin/main`.
  **Nie auf `main` pushen.**
- Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten.
- Code-Bezeichner englisch, UI-Texte deutsch; neue Texte nach `strings.xml` in allen drei
  Ressourcenordnern.
- `./gradlew assembleDebug lintDebug test` grün, `ktlintCheck` ohne neue Befunde in den
  geänderten Dateien. **Ausgabe in den PR.**
- **Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben.**
- **Keine Schemaänderung.** F-15 kommt ohne aus — siehe unten. Wenn du glaubst, doch eine zu
  brauchen, halte an und frag.
- Draft-PR gegen `main`, Definition of Done nach `AGENTS.md` §7.

---

## 1 · F-08 — Die Gebietseinstufung blockiert alles und ist unsichtbar

Der Kern des Problems, deshalb zuerst.

`ReportConfigEntity.gebietseinstufung` hat den Default `""` (`data/ReportConfigEntity.kt:48`).
Solange dort nichts steht, **schlägt jede Berichtserzeugung fehl** — aber die Gebietseinstufung
wird im Berichtsflow nirgends abgefragt oder auch nur erwähnt. Sie liegt in den Einstellungen,
in einem anderen Tab.

Ein Erstnutzer kann diesen Fehler nicht selbst auflösen, ohne zu raten.

**Was zu tun ist:** Die Gebietseinstufung im Berichtsflow sichtbar machen — als Vorbedingung,
die vor dem Erstellen geprüft und an Ort und Stelle gesetzt werden kann. `ReportAreaSelection`
existiert bereits und ist wiederverwendbar.

**Falle:** `ReportAreaSelection` enthält bewusst deaktivierte Einträge (ungeprüfte Gebiete).
Diese Sperre ist fachlich gewollt — nicht entfernen. Der zugehörige Test
`ReportConfigSettingsTest > ungepruefteGebieteKoennenNichtAusgewaehltWerden` muss grün bleiben.

---

## 2 · F-07 — Vorprüfung läuft erst nach dem Klick

Alle Vorbedingungen werden ausschließlich **innerhalb** von `erzeugen()` geprüft
(`BerichtErstellenSheet.kt:109-140`). Der Knopf ist also immer aktiv, und der Nutzer erfährt
erst nach dem Tippen, dass etwas fehlt — ohne Hinweis, was.

**Was zu tun ist:** Die Vorbedingungen aus `erzeugen()` in eine reine, testbare Funktion ziehen
und ihr Ergebnis **vor** dem Klick anzeigen: Knopf deaktiviert plus sichtbare Begründung je
fehlender Bedingung.

**Falle:** Ein deaktivierter Knopf ohne Begründung ist keine Verbesserung, sondern F-23 in neu.
Die Begründung ist Pflicht, nicht optional.

---

## 3 · F-28 — Zeitraum wird weder vorbelegt noch gemerkt

Der Berichtszeitraum startet jedes Mal leer, obwohl die App weiß, wann zuletzt gemessen wurde.

**Was zu tun ist:** `initialHighEndRange` aus der zuletzt beendeten Session ableiten
(`sessionDao.letzte()`), die Presets des alten Dialogs übernehmen und den zuletzt gewählten
Zeitraum in `SettingsManager` merken.

**Persistenz:** zwei neue `Long`-Schlüssel in `SettingsManager`. **Kein Room, keine Migration.**

---

## 4 · F-15 — Jede Speicherung legt eine neue Stammdatenzeile an

`stammdaten_verlauf` bekommt bei jedem Speichern eine neue Zeile, auch wenn sich nichts geändert
hat. Zusätzlich werden alle 13 Felder gleich behandelt, obwohl drei davon zeitgebunden sind
(Kalibrierung, Wetter, Datenqualitätshinweis) und zehn praktisch konstant.

**Owner-Entscheidung vom 25.09.2026:** Tagesregel mit Korrekturmöglichkeit — wurden die
Parameter an einem Tag einmal bestätigt, wird an diesem Tag nicht erneut aufgefordert; korrigieren
muss der Nutzer jederzeit können.

**Was zu tun ist:** Vor dem Schreiben mit der letzten Zeile vergleichen und nur bei
tatsächlicher Änderung eine neue anlegen. Das ist eine **reine Vergleichsfunktion** — kein
Schemawechsel.

**Falle:** Der Vergleich muss feldweise und auf normalisierten Werten arbeiten (getrimmt,
Groß-/Kleinschreibung dort egal, wo sie fachlich egal ist). Ein naiver `==` auf dem ganzen
Entity schlägt an Zeitstempeln oder IDs fehl und legt weiter Duplikate an.

**Residualrisiko, das der Audit nennt und das du im PR erwähnen sollst:** Ein am Morgen
bestätigter Wetterwert kann abends veraltet sein. Empfehlung des Audits: den
Bestätigungszeitpunkt sichtbar mitführen. Setz das um, wenn es ohne Schemaänderung geht;
andernfalls beschreib es im PR als offenen Punkt.

---

## 5 · Reihenfolge

F-08 zuerst (löst den Kern), dann F-07 (baut auf derselben Prüffunktion auf), dann F-28, zuletzt
F-15. Jeder Block ein eigener Commit.

---

## 6 · Was ausdrücklich **nicht** Teil des Auftrags ist

- **F-16** (Sheets erscheinen zweimal je Messvorgang) → Phase 4, braucht Room-Migration 25 → 26.
- **S-4** (Bericht-Tab als Arbeitsplatz statt Zwischenseite) → Phase 6, struktureller Umbau.
- Die Sperre ungeprüfter Gebiete aufheben. Sie ist fachlich gewollt.
- Jede Schemaänderung.

---

## 7 · Definition of Done

1. `assembleDebug`, `lintDebug`, `test` grün — **Ausgabe im PR**.
2. Room-Migrationstests grün (Nachweis im PR, auch ohne Schemaänderung).
3. `BerichtErstellenSheetTest` und die instrumentierte Variante angepasst statt umgangen.
4. Neue JVM-Tests für die herausgezogene Vorbedingungsprüfung und für den Stammdatenvergleich —
   beides sind reine Funktionen und damit gut testbar.
5. Im PR belegen: Tapzahl im Normalfall und im Fehlerfall vorher/nachher.
6. Draft-PR gegen `main`, Kurzmeldung an den Owner.
