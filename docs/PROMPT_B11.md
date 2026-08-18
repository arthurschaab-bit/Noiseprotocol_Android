# Prompt: B-11 — 16-KB-Seitengröße und ein verschluckter Fehler

Für eine eigene Session. **Ohne Hardware umsetzbar**, unabhängig vom Bluetooth-Pfad — die
betroffenen Dateien überschneiden sich nicht mit M4/M5.

Ersetzt Abschnitt B aus `docs/PROMPT_M1.md`, der noch die Paketstruktur vor M1 voraussetzte.

---

```text
Du setzt einen abgegrenzten Fix um. Der Plan steht — deine Aufgabe ist die Umsetzung.

PROJEKT
Android-App "Lärmprotokoll" (com.example.lrmprotokoll), Kotlin + Compose + Room.
Repository: arthurschaab-bit/Noiseprotocol_Android, Default-Branch: main.
Branch für diese Arbeit: fix/b11-16kb-seitengroesse

ZUERST LESEN
- README.md, Abschnitt "Bekannte Einschränkungen"
- docs/PROMPT_UMSETZUNG.md Abschnitt B — Arbeitsregeln, gelten unverändert

ANLASS
Auf einem Pixel erscheint beim Start der App die Systemwarnung:

  "Diese App ist nicht mit 16 KB kompatibel. Die ELF-Abgleichsprüfung ist fehlgeschlagen.
   Folgende Bibliotheken sind nicht für 16 KB optimiert:
   lib/arm64-v8a/libtask_audio_jni.so : LOAD-Segment stimmt nicht überein"

Die Bibliothek stammt aus org.tensorflow:tensorflow-lite-task-audio:0.4.4 und treibt die
YAMNet-Klassifikation an.

=== PROBLEM 1: Die Abhängigkeit ===

libtask_audio_jni.so ist vorkompiliert und nicht auf 16 KB ausgerichtet. Die Ausrichtung lässt
sich NICHT nachträglich korrigieren — weder über useLegacyPackaging noch über zipalign. Der
einzige Weg ist der Austausch der Abhängigkeit. Das Paket ist ohnehin abgekündigt.

Migriere auf einen gepflegten, 16-KB-ausgerichteten Nachfolger:
  - com.google.mediapipe:tasks-audio  (direkter Nachfolger für diesen Anwendungsfall), oder
  - com.google.ai.edge.litert

Prüfe die aktuelle Dokumentation des gewählten Pakets, statt die alte API-Signatur zu raten —
sie ist NICHT identisch.

Betroffen ist im Wesentlichen app/src/main/java/com/example/lrmprotokoll/audio/NoiseClassifier.kt
(rund 110 Zeilen). Das Modell app/src/main/assets/yamnet.tflite soll weiter genutzt werden.

Verhalten, das unverändert erhalten bleiben MUSS:
- Label-Mapping ins Deutsche (Hammering -> Hämmern, Drill -> Bohren usw.)
- Abgleich mit gelernten Referenzgeräuschen aus der Tabelle reference_sounds
- Konfidenzschwelle aus SettingsManager.aiConfidenceThreshold
- Rückgabe null, wenn nichts Brauchbares erkannt wurde

=== PROBLEM 2: Der Fehler, der den Fehler verschluckt ===

NoiseClassifier fängt an drei Stellen mit `catch (e: Exception)` ab, darunter im init-Block
beim Laden des Modells. Ein fehlgeschlagenes Laden einer nativen Bibliothek wirft aber
UnsatisfiedLinkError — ein Error, KEIN Exception. Der Catch greift also ausgerechnet im
realistischen Fall nicht.

Das wiegt schwer, weil AudioRecordingService.onCreate() den NoiseClassifier direkt konstruiert:
Ein Error dort reißt den gesamten Foreground Service mit, und damit die Lärmaufzeichnung.

Zwei Änderungen:

1. Auf `catch (e: Throwable)` umstellen, mit Log-Ausgabe. (Bei einem Migrationsziel, das keine
   native Bibliothek mehr lädt, bleibt das trotzdem richtig — es kostet nichts und schützt
   gegen die nächste native Abhängigkeit.)

2. Absichern, dass ein nicht geladener Klassifikator die Aufzeichnung NICHT verhindert:
   Pegelmessung, Schwellwert-Trigger und WAV-Aufnahme müssen weiterlaufen, es entfällt
   lediglich das Label. Das ist die wichtigere Eigenschaft — ein Lärmprotokoll ohne
   Klassifikation ist brauchbar, eines ohne Aufnahme nicht.

=== NICHT TEIL DIESES AUFTRAGS ===
Bluetooth, ConnectionSupervisor, Persistenz (M4), Alarmierung (M5), Drive-Sync (M7b),
die applicationId (B-6), und die vier Altbefunde aus docs/PROMPT_REVIEW.md Schritt 5.

=== DEFINITION OF DONE ===
- ./gradlew assembleDebug und ./gradlew test grün — Ausgabe im PR zeigen, nicht behaupten.
  Fehlt ein Android SDK: sdkmanager "platforms;android-36" "build-tools;36.0.0", dann
  local.properties (sdk.dir=...).
- Alle bestehenden Tests weiterhin grün, insbesondere die beiden Room-Migrationstests
- Test, der belegt: schlägt die Klassifikator-Initialisierung fehl, läuft die Aufzeichnung
  weiter. Dafür muss NoiseClassifier vermutlich injizierbar oder hinter eine kleine
  Schnittstelle gelegt werden — das ist in Ordnung, aber halte den Eingriff klein.
- Für jeden neuen Test eine Gegenprobe: Schlägt er fehl, wenn man die zugehörige Logik
  entfernt? Tests, die in keiner Fassung fehlschlagen können, gehören nicht in den PR.
- Nachweis, dass die Bibliothek weg ist:
    unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "\.so"
  Ergebnis im PR nennen, ebenso die APK-Größe vorher und nachher.
- Draft-PR gegen main mit: was geändert, was verifiziert (Befehl und Ergebnis), was offen blieb.

=== ENDABNAHME AM GERÄT — im PR als offen markieren, nicht behaupten ===
- Die 16-KB-Warnung erscheint beim Start nicht mehr
- Die Klassifikation liefert weiterhin brauchbare deutsche Labels — mit einem realen Geräusch
  gegenprüfen, nicht nur "stürzt nicht ab"
- Gelernte Referenzgeräusche werden weiterhin erkannt

Der zweite Punkt ist der wichtigere: Eine Migration, nach der die Klassifikation zwar läuft,
aber nur noch Unsinn liefert, wäre schlimmer als der Ausgangszustand.
```

---

## Hintergrund zur Dringlichkeit

Kein akuter Blocker: Auf einem Gerät im 4-KB-Modus ist die Meldung nur eine Warnung, die App
läuft. Zwei Gründe, es trotzdem nicht liegen zu lassen:

**Auf einem Gerät im 16-KB-Modus** — aktuelle Pixel können das — schlägt das Laden fehl, und
wegen Problem 2 nimmt das den Foreground Service mit. Prüfen lässt sich der Modus mit
`adb shell getconf PAGE_SIZE`.

**Seit November 2025** ist 16-KB-Unterstützung Pflicht für Play-Uploads mit targetSdk 35+.
Für die interne Verteilung irrelevant, für eine spätere Veröffentlichung ein harter Blocker.
