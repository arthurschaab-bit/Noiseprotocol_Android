# Kennzahlen (automatisch generiert)

**Nicht von Hand pflegen und nicht von Hand editieren.** Diese Datei entsteht durch
`./gradlew generiereKennzahlen` aus den tatsächlichen Quellen (Testergebnisse, Quellcode) -
siehe Kommentar über der Gradle-Task in `app/build.gradle.kts` (Prüfprotokoll Frage 10,
Owner-Entscheidung vom 11.09.2026: „Baue etwas, das es aus dem Code automatisch generiert
wird“, Anlass: veraltete Testzahlen und ein falscher Kadenz-Toleranz-Wert in README.md).

Generiert am: Fri Sep 11 10:51:08 UTC 2026

## Tests
- JVM-/Robolectric-Tests: **884** (aus 155 XML-Berichten unter `build/test-results/testDebugUnitTest`) · 0 Failures, 0 Errors
- Instrumentierte Tests (Emulator): **73** `@Test`-Annotationen in 20 Dateien unter `src/androidTest`
  - Gezählt aus dem Quellcode, NICHT hier ausgeführt (kein durchgereichter Bluetooth-/Kamera-Adapter in dieser Umgebung, siehe `docs/TESTEN_EINES_PR.md`).

## Datenbank
- Room-Schemaversion: **19** (`AppDatabase.kt`)

## BLE / Kadenz
- Tatsächlich verwendete Kadenz-Toleranz (`AppContainer.cadenceTolerance`): **±50%**
  - Der `ConnectionSupervisor`-Klassen-Default (nur in Tests aktiv) ist ±20% - siehe dessen KDoc.

## Schwellwerte (Trigger-Defaults)
- Mikrofon-Schwelle (`db_threshold`): **60.0 dB**
- Messgerät-Schwelle (`meter_db_threshold`): **60.0 dBA**

## Build
- minSdk: **29** · compileSdk: **36**
