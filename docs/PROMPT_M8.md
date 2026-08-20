# Prompt: M8 — Härtung (Release-Build, Herstellerspezifika)

Für eine **hardwarefreie** Session (Codex laut AGENTS.md §8, oder Claude Code) — reine
Build-Konfiguration und eine kleine, isolierte Ableitungslogik, kein BLE-Protokoll-Anteil.
Voraussetzung: M7c ist auf `main`.

**Wichtig, zuerst lesen:** M8 ist im Plan (Abschnitt 12) mit vier Inhalten definiert:
Chaos-Checkliste, 24-h-Dauerlauf, Herstellerspezifika, Release-Build. Die ersten beiden
**brauchen ein echtes Gerät** und sind bereits in
[`docs/CHECKLISTE_GERAETETEST.md`](CHECKLISTE_GERAETETEST.md) erfasst (Teil C = Robustheit,
Teil D = Dauerlauf) — sie sind **nicht Teil dieses Prompts**. Dieser Prompt deckt nur die zwei
Anteile von M8 ab, die ohne Hardware erledigt werden können: Release-Build härten und
Herstellerspezifika als Software-Hinweis abfedern.

---

```text
Du setzt den hardwarefreien Teil von M8 um (Plan Abschnitt 12, Zeile "Härtung"). Lies zuerst:

ZUERST LESEN
1. README.md — Statusüberblick, Abschnitt "Bekannte Einschränkungen"
2. docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md Abschnitt 12 (Meilensteine) — was M8 laut
   Plan umfasst, und warum Chaos-Checkliste/24h-Dauerlauf hier NICHT gemeint sind
3. docs/CHECKLISTE_GERAETETEST.md Teil C und D — dort stehen die hardwarepflichtigen M8-Anteile
   bereits, nicht hier duplizieren
4. docs/PROMPT_UMSETZUNG.md Abschnitt B — Arbeitsregeln, gelten unverändert

PROJEKT
Android-App "Lärmprotokoll" (com.example.lrmprotokoll), Kotlin + Compose + Room.
Repository: arthurschaab-bit/Noiseprotocol_Android, Default-Branch: main.
Branch für diese Arbeit: feature/m8-haertung

=== AUFGABE 1: Release-Build härten (R8/Minify aktivieren) ===

app/build.gradle.kts hat aktuell `isMinifyEnabled = false` im release-Buildtype, und
app/proguard-rules.pro enthält nur die Standard-Kommentarvorlage (keine einzige aktive Regel).
Ein unminifizierter Release-Build ist größer und leichter zu reverse-engineeren als nötig —
kein funktionales Risiko, aber ein offener Punkt aus der Plan-Härtung.

- `isMinifyEnabled = true` und `isShrinkResources = true` im release-Buildtype setzen.
- `./gradlew assembleRelease` laufen lassen und die R8-Ausgabe genau lesen. Für jede
  "Missing class"/"Warning"-Zeile klären, ob sie harmlos ist (Bibliothek deckt sich bereits über
  ihre eigenen consumer-rules.pro ab) oder eine `-keep`-Regel in proguard-rules.pro braucht.
  Besonders zu prüfen, weil sie Reflection/dynamisches Laden nutzen:
  - **WorkManager**: instanziiert Worker-Klassen reflektiv. Die vier CoroutineWorker
    (`RetentionWorker`, `DiagnosticLogCleanupWorker`, `DriveSyncWorker`, `HeartbeatWorker`)
    müssen erhalten bleiben, plus deren `Companion`/Factory-Methoden, falls WorkManager sie
    per Konstruktor-Reflection aufruft.
  - **Room-Entities/DAOs**: durch KSP größtenteils generierter Code, aber `SessionEntity`,
    `MeasurementEntity` & Co. sollten trotzdem stichprobenartig geprüft werden (Datenklassen mit
    Default-Werten können durch Aggressive Optimierung Probleme bekommen).
  - **Credentials/Google Sign-In/Play-Services-Auth** (`androidx.credentials`, `googleid`,
    `play-services-auth`) und **security-crypto** (Keystore) — beide nutzen an Stellen
    Reflection, meist über eigene consumer-rules.pro abgedeckt, aber im R8-Log verifizieren statt
    annehmen.
  - **MediaPipe Tasks Audio** (YAMNet-Ersatz aus B-11) — hat native/JNI-Anteile, prüfen ob R8
    dafür etwas strippt, das zur Laufzeit gebraucht wird.
  - Keine `-dontwarn`-Zeile ohne kurzen Kommentar, warum die Warnung unschädlich ist — sonst
    verschluckt sie beim nächsten Dependency-Update stillschweigend eine echte neue Warnung.
- `./gradlew test` muss weiterhin grün bleiben (debug-Variante ist von diesen Build-Type-
  Änderungen nicht betroffen, aber sicherstellen, dass nichts anderes kaputtgegangen ist).

**Nicht testbar ohne Gerät:** Ob die minifizierte App zur Laufzeit tatsächlich fehlerfrei
startet und alle Features funktionieren (R8 kann zur Compile-Zeit unauffällig bleiben und trotzdem
zur Laufzeit z. B. per Reflection auf eine entfernte Methode stoßen). Das MUSS im README als
offener Punkt dokumentiert werden (siehe Aufgabe 3), nicht verschwiegen werden.

=== AUFGABE 2: Herstellerspezifika — OEM-Autostart-Hinweis ===

`SettingsScreen.kt` prüft bereits `PowerManager.isIgnoringBatteryOptimizations()` und bietet
einen Freischalt-Weg für die Standard-Android-Akkuoptimierung an. Das reicht auf vielen Geräten
NICHT: Xiaomi (MIUI), Huawei (EMUI/HarmonyOS), Oppo (ColorOS), Vivo, OnePlus (OxygenOS) und
teils Samsung haben zusätzlich eine herstellereigene "Autostart"/"Geschützte Apps"-Einstellung
außerhalb des Standard-Android-Systems — ohne die killt das ROM den Foreground Service trotz
gewährter Akkuoptimierungs-Ausnahme (genau das Risiko, das der Plan unter "Hersteller-ROM killt
den Foreground Service" in Abschnitt 13 nennt).

- Reine Ableitungsfunktion (analog zum Muster in `messreihe/DashboardStatus.kt`): nimmt einen
  Hersteller-String entgegen (NICHT direkt `Build.MANUFACTURER` in der Funktion lesen, sondern
  als Parameter — das macht sie ohne Robolectric/Gerät per JVM-Unit-Test prüfbar) und liefert
  zurück, ob eine Herstellerwarnung angezeigt werden soll und welcher Hinweistext dazu gehört.
  Bekannte Hersteller-Strings und ihre üblichen Einstellungs-Package/Activity-Namen recherchieren
  (gut dokumentiertes Android-Fragmentierungsproblem, mehrere Referenzimplementierungen
  öffentlich einsehbar) — als benannte Konstanten, nicht als Magic Strings.
- In `SettingsScreen.kt` (oder wo die Akkuoptimierungs-Karte bereits sitzt): zusätzliche Karte/
  Zeile, die bei erkanntem Hersteller den Hinweistext zeigt und einen Button anbietet, der
  versucht, die herstellereigene Einstellungsseite per `Intent` zu öffnen.
- **Der Intent muss robust gegen `ActivityNotFoundException` sein** — die Package/Activity-Namen
  variieren zwischen ROM-Versionen und sind nicht garantiert vorhanden. Bei Fehlschlag bleibt
  wenigstens der Hinweistext sichtbar, kein Absturz, keine stille Fehlfunktion.
- Bewusst zurückhaltend bleiben: nur Textinformation + Best-Effort-Deep-Link, kein automatisches
  Navigieren ohne Nutzeraktion — dasselbe Prinzip wie die ehrliche Bonding-Kennzeichnung aus
  M6-A (`GeraetePinning`): der App-Zustand darstellen, nicht Sicherheit/Zuverlässigkeit
  vortäuschen, die sie nicht hat.

NICHT TEIL VON M8 (dieser Prompt)
- Chaos-Checkliste und 24-h-Dauerlauf — brauchen echtes Gerät, bereits in
  docs/CHECKLISTE_GERAETETEST.md Teil C/D erfasst.
- M9 (FCM-Zielbild) — separater, als optional markierter Meilenstein.
- Keine Änderung an bestehender Business-Logik (Trigger, Alarmierung, Sync) — reine
  Härtungsmaßnahmen an Build-Konfiguration und einer zusätzlichen Hinweiskarte.

TESTS
- Aufgabe 1: `./gradlew assembleRelease` grün, keine unerklärten R8-"Missing class"-Warnungen.
  `./gradlew test` weiterhin grün.
- Aufgabe 2: Die Hersteller-Erkennungs-/Hinweislogik als reine Funktion mit JVM-Unit-Tests
  (bekannte Hersteller → erwarteter Hinweis, unbekannter Hersteller → kein Hinweis, Groß-/
  Kleinschreibung/Whitespace in `Build.MANUFACTURER`-artigen Eingaben). Für jeden neuen Test
  eine Gegenprobe: Schlägt er fehl, wenn man die zugehörige Logik entfernt oder vertauscht?
  Das Intent-Fallback-Verhalten (ActivityNotFoundException abgefangen) zusätzlich per
  Compose-/Robolectric-Test, falls sich das sauber simulieren lässt — sonst im PR explizit als
  "nur durch Code-Review, nicht durch Test belegt" kennzeichnen.

DEFINITION OF DONE
- ./gradlew assembleDebug, ./gradlew assembleRelease und ./gradlew test grün — Ausgabe im PR
  zeigen, nicht nur behaupten.
- Beide Room-Migrationstests weiterhin grün.
- README aktualisiert: M8-Zeile in der Status-Tabelle ergänzen (Release-Build + Herstellerhinweis
  erledigt, Chaos-Checkliste/24h-Dauerlauf ausdrücklich weiterhin offen, Teil des Gerätetests).
  Neuer Punkt unter "Bekannte Einschränkungen": minifizierter Release-Build ist nur durch
  `assembleRelease` und R8-Log-Prüfung verifiziert, nie an echtem Gerät gestartet.
- Draft-PR gegen main mit: was geändert, was verifiziert (Befehl und Ergebnis), was offen.
```

---

## Warum als eigener, verkleinerter Zuschnitt statt volles M8

Der Plan zählt Chaos-Checkliste und 24-h-Dauerlauf zu M8, aber beide sind inhaltlich bereits
Teil des ohnehin ausstehenden Gerätetests (`docs/CHECKLISTE_GERAETETEST.md`, Teil C/D) — sie
dort ein zweites Mal als "M8" abzuarbeiten würde denselben Test doppelt zählen. Diese Prompt-
Fassung trennt sauber: Was eine Coding-Session ohne Gerät leisten kann (Release-Build,
Herstellerhinweis), bleibt hier. Was ein echtes Gerät braucht, bleibt dort, wo es bereits steht.

## Eine Falle, die im Auftrag steht

**`isMinifyEnabled = true` ist kein Ein-Zeiler.** Wer die Zeile umstellt, `assembleRelease`
einmal grün sieht und aufhört, hat nichts geprüft — R8 kann beim Bauen unauffällig bleiben und
trotzdem zur Laufzeit brechen (gestrippte, aber per Reflection gebrauchte Klasse). Die R8-Log-
Ausgabe muss tatsächlich gelesen werden, nicht nur der Exit-Code.
