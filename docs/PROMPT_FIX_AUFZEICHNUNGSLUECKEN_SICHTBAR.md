# Prompt: Aufzeichnungslücken in Tages-CSV und Pegelverlauf sichtbar machen

**Priorität 8, Welle 3.** Befund G aus [`BEFUNDE_BUNDLES_2026-10-07.md`](BEFUNDE_BUNDLES_2026-10-07.md).

**Owner-Freigabe vom 07.10.2026:** „Ja, mach die Vorschläge.“ Dazu gehört „Lücken sichtbar machen:
in der CSV und im Verlauf“. Grundsätzlich ist das freigegeben. **Die Einzelheiten E1–E4 in
Abschnitt 5 sind offen.** Sie ändern das Dateiformat aus Plan 8.4.2 und damit eine Beweisdatei.
Kläre sie mit dem Owner, **bevor** du Code schreibst (AGENTS.md §8a).

Technisch hängt dieser Auftrag von keinem anderen ab. Die CSV unterscheidet nicht zwischen einem
ausdrücklichen Stopp und einem Ausfall, beides ist „keine Aufzeichnung“. Den Grund einer
Unterbrechung hält `PROMPT_FIX_AUFZEICHNUNG_FORTSETZEN.md` im Diagnoseprotokoll fest.

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

Lies `AGENTS.md` vollständig, sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann
  `git switch -c fix/aufzeichnungsluecken-sichtbar origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten. CSV
  und Chart in getrennten Commits.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`
  (keine neuen Befunde in den geänderten Dateien). Nur bei Grün committen und pushen.
- **Nur handgeschriebene Fakes.** Kein Mockito, kein MockK. **Keine Schemaänderung** ohne
  ausdrückliche Owner-Freigabe (siehe E2).
- **Tests nicht abschwächen.** Ein bestehender Test, der das alte Verhalten festschreibt, wird
  umgeschrieben, nicht gelöscht. Die Spezifikationsänderung steht ausdrücklich im PR.
- Plan 8.4.2 wird an das neue Format angepasst. Das ist eine Plan-Abweichung, die im PR geflaggt
  wird (AGENTS.md §2).
- UI-Texte in **allen** Ressourcenordnern (`values`, `values-de`, `values-en`).
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7. UI-Anteil: Screenshot vom
  Emulator in den PR, falls möglich (AGENTS.md §9 sieht für UI-lastige Arbeit Emulator-Feedback
  vor).

---

## 1 · Befund

- **Die CSV vom 06.10.2026 endet um 12:41:23**, obwohl sie am 07.10. um 06:55 neu erzeugt wurde.
  Für den Rest des Tages gibt es keine Zeile. Der Owner hielt den 18-stündigen Ausfall deshalb
  zunächst für einen Anzeigefehler.
- **Die CSV vom 01.10. beginnt erst um 06:28:32.** Die Stunden davor fehlen ebenso
  kommentarlos.
- **Das Verhalten ist gewollt und durch einen Test festgeschrieben:** `PegelAggregator.aggregiere`
  schneidet leere Fenster vor der ersten und nach der letzten Zeile mit Daten ab. Test:
  `leereZeitenVorUndNachMessungWerdenNichtAlsLeereZeilenErzeugt`. Lücken **zwischen** Daten werden
  als `KEINE_VERBINDUNG` gefüllt (`fensterOhneSampleWirdAlsLueckeAusgegebenNichtAusgelassen`). Bei
  Abschnitten übernimmt das `DriveSyncCoordinator.aggregiereInAbschnitten`.
- **Das widerspricht dem Zweck aus Plan 8.4.2:** „Lücken müssen als Lücken sichtbar sein. Eine
  Messreihe, in der Ausfälle einfach fehlen, ist forensisch wertlos.“ Ein Ausfall über das
  Tagesende hinweg fehlt einfach.
- **Die Bezeichnung ist irreführend:** Auch die Lücke 04:26–06:53 am 06.10., in der die App gar
  nicht lief, heißt `KEINE_VERBINDUNG`.
- **Pegelverlauf:** `PegelverlaufChart` (genutzt von `LiveCockpitCard` und
  `ProtokollDetailScreen`) zeichnet rote Bänder nur für Verbindungsausfälle **innerhalb** einer
  Session (`leiteAusfallbaenderAb` aus `ConnectionEvent`s). Zeit zwischen zwei Sessions, in der die
  App nicht lief, ist nicht markiert.
- **Woher wir wissen, wann aufgezeichnet wurde, ohne Schemaänderung:** aus den Sessions
  (`SessionEntity.startedAt`/`endedAt`). Eine verwaiste Session schließt
  `MeasurementRecorder.schliesseVerwaisteSessions()` auf den Zeitpunkt des letzten Messwerts.
  Zeit außerhalb jeder Session heißt: Es lief keine Aufzeichnung.

---

## 2 · Auftrag (Umfang je nach Antworten auf E1–E4)

### Schritt 1 — Reine Funktion für Aufzeichnungsintervalle

- `fun aufzeichnungsLuecken(sessions: List<SessionEntity>, von: Long, bis: Long): List<Zeitraum>`
  in `messreihe/`. Sie bildet die Vereinigung aller Session-Intervalle und liefert deren
  Komplement in `[von, bis)`.
- Mikrofon- und Messgerät-Sessions zählen beide als Aufzeichnung, Überlappungen werden
  verschmolzen.
- Eine offene Session (`endedAt == null`) gilt bis `bis`, wenn sie die aktive ist. Prüfe, wie
  `SessionDao.offeneSession` das unterscheidet.

### Schritt 2 — Tages-CSV

- Zeitraum laut E1 füllen.
- Fenster ohne Samples **außerhalb** jeder Session bekommen die Quelle laut E2, solche
  **innerhalb** einer Session bleiben `KEINE_VERBINDUNG`.
- `AggregatZeile` bekommt dafür einen Parameter, kein neues Literal an mehreren Stellen.
- Das Stitching in `aggregiereInAbschnitten` muss dieselbe Unterscheidung treffen. Lies dessen
  KDoc vollständig, der Abschnitt ist fehleranfällig (Rasterausrichtung, Nachbesserung
  24.09.2026).
- Speicher: Ein voller Tag hat 86.400 Zeilen, heute sind es bis 73.000. Die Abschnittslogik aus
  dem OOM-Fix (`PROMPT_FIX_OOM_DRIVE_SYNC.md`) darf nicht wieder alle Rohwerte eines Tages
  gleichzeitig laden.
- Suche alle Leser des CSV-Formats: `grep -rn "KEINE_VERBINDUNG\|QUELLE_" app/src`, außerdem
  Python-Code unter `app/src/main/python` und die Berichte. Liest ein Bericht die Quelle,
  passe ihn an oder melde das im PR.
- **Plan 8.4.2 anpassen:** das Beispiel um eine Zeile mit dem neuen Wert ergänzen und die
  Begründung in einem Satz.

### Schritt 3 — Pegelverlauf

- `PegelverlaufChart` erhält zusätzlich die Lücken aus Schritt 1 und zeichnet sie **anders als
  die roten Ausfallbänder**, laut E4.
- Legende bzw. Beschriftung: „Keine Aufzeichnung“ gegenüber „Verbindungsausfall“.
- Prüfe, welchen Zeitraum der Chart im Cockpit und im Detail zeigt (`ChartDaten.kt`). Lücken
  außerhalb des angezeigten Zeitraums spielen keine Rolle.

### Nicht Teil dieses Auftrags

- **Keine Änderung an Berichten, Datenverfügbarkeit oder Messintegrität**
  (`Datenverfuegbarkeit.kt`, `Messintegritaet.kt`, `PeriodenBerichtExport`, `GesamtberichtExport`).
  Die Berichte sind für § 287 ZPO gedacht, ihre Semantik entscheidet der Owner gesondert. Melde im
  PR, ob und wo sie Aufzeichnungslücken heute anders zählen als die CSV.
- Keine Tage ganz ohne Daten, es sei denn, E3 sagt es.

---

## 3 · Tests (JVM, handgeschriebene Fakes)

1. `aufzeichnungsLuecken`:
   - keine Session → ganzer Bereich;
   - zwei überlappende → keine Lücke dazwischen;
   - offene aktive Session;
   - Session über die Tagesgrenze.
2. **Nachbau des 06.10.:**
   - Samples 00:00–04:26:43, Session-Ende 04:26:43;
   - neue Session 06:53:44–12:41:23, Samples darin;
   - die CSV hat 86.400 Zeilen;
   - 04:26:44–06:53:43 und 12:41:24–23:59:59 tragen den Wert aus E2.
3. Lücke **innerhalb** einer Session bleibt `KEINE_VERBINDUNG`.
4. `leereZeitenVorUndNachMessungWerdenNichtAlsLeereZeilenErzeugt` wird nach E1 **umgeschrieben**.
   Der neue Name sagt, was jetzt gilt, und der PR nennt die Änderung ausdrücklich.
5. Abschnittsgrenzen: Eine Lücke, die eine Abschnittsgrenze von `aggregiereInAbschnitten`
   überspannt, erscheint vollständig und mit dem richtigen Wert.
6. Chart: Bei gegebenen Lücken erhält `PegelverlaufChart` sie. Ein Compose- oder
   Semantik-Test prüft die Beschriftung „Keine Aufzeichnung“.

Die Tests 2 und 5 müssen ohne deine Änderung rot sein. Zeig das im PR.

---

## 4 · Akzeptanzkriterien

- [ ] Eine Tages-CSV zeigt jede Aufzeichnungslücke im vereinbarten Zeitraum als eigene Zeilen
      (Test 2).
- [ ] „Keine Aufzeichnung“ ist von „Keine Verbindung“ unterscheidbar (Tests 2 und 3).
- [ ] Der Pegelverlauf zeigt Aufzeichnungslücken sichtbar anders als Verbindungsausfälle
      (Test 6, Screenshot).
- [ ] Plan 8.4.2 ist angepasst, die Abweichung im PR geflaggt.
- [ ] Kein Leser des CSV-Formats bricht. Die Liste der Leser steht im PR.
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 5 · Offene Entscheidungen (vor Umsetzung beim Owner klären, AGENTS.md §8a)

- **E1 — Welcher Zeitraum wird gefüllt?**
  - (a) Abgeschlossene Tage vollständig 00:00:00–23:59:59, der laufende Tag bis zum Zeitpunkt
    des Syncs.
  - (b) Nur zwischen der ersten und der letzten Session des Tages.

  **Empfehlung: (a).** Nur (a) hätte den Ausfall vom 06.10. sichtbar gemacht.
- **E2 — Wie heißt eine Zeile ohne laufende Aufzeichnung?**
  - (a) Neuer Wert `KEINE_AUFZEICHNUNG`, abgeleitet aus den Sessions, ohne Schemaänderung.
  - (b) Wie (a), aber aus einer neuen Tabelle mit Dienst-Laufzeiten. Das ist genauer, braucht
    aber Schema 26 und eine Migration.
  - (c) Bei `KEINE_VERBINDUNG` bleiben.

  **Empfehlung: (a).** Bekannte Unschärfe: Im reinen Messgerät-Betrieb gibt es vor dem ersten
  Frame noch keine Session. Diese Minuten würden als `KEINE_AUFZEICHNUNG` erscheinen, obwohl der
  Dienst schon lief. Der PR quantifiziert das.
- **E3 — Tage ganz ohne Daten (wie 03./04.10.)?**
  - (a) Weiterhin keine Datei.
  - (b) Eine Datei voller `KEINE_AUFZEICHNUNG`.

  **Empfehlung: (a)**, solange im Ordner sonst nichts auf einen Ausfall hindeutet. Ein bewusst
  ausgeschaltetes Wochenende erzeugt sonst Dateien mit 86.400 leeren Zeilen.
- **E4 — Darstellung im Verlauf?** **Empfehlung:** graues, schraffiertes Band mit der Beschriftung
  „Keine Aufzeichnung“, die roten Bänder bleiben für Verbindungsausfälle. Gilt für den Chart im
  Cockpit und im Protokoll-Detail, **nicht** in den PDF-Berichten (siehe „Nicht Teil dieses
  Auftrags“).

## 6 · Gerätecheck (macht der Owner nach dem Merge)

1. Aufzeichnung kurz beenden, nach 10 min wieder starten.
2. Im Cockpit-Verlauf erscheint ein graues Band „Keine Aufzeichnung“.
3. Nach dem nächsten Drive-Sync enthält die Tages-CSV diese 10 min als `KEINE_AUFZEICHNUNG`.
   Die Datei des Vortags reicht bis 23:59:59.

Trag diese Checks als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein, die
Ergebnis-Spalte bleibt leer.
