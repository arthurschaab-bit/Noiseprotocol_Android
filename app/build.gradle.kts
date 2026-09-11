import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
}

val releaseStoreFile = (findProperty("releaseStoreFile") as String?)?.let { file(it) }

// Praefprotokoll C-6-Rest (Owner-Entscheidung vom 11.09.2026, nachdem das Repository bestaetigt
// oeffentlich wurde: "Keystore jetzt im Code rotieren"): derselbe Aussen-Mechanismus wie bei
// releaseStoreFile darunter - der Dateipfad kommt ueber eine Gradle-Property herein, nicht mehr
// als eingecheckte Datei im Repository.
val debugStoreFile = (findProperty("debugStoreFile") as String?)?.let { file(it) }

android {
    namespace = "com.example.lrmprotokoll"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.lrmprotokoll"
        minSdk = 29
        targetSdk = 36
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("boolean", "DIAGNOSTICS_REMOTE_ENABLED", "true")
        buildConfigField("String", "SENTRY_DSN", "\"\"")
    }

    signingConfigs {
        // Praefprotokoll C-6-Rest: der frueher hier eingecheckte app/debug.keystore ist durch
        // die oeffentliche Sichtbarkeit des Repositories unwiderruflich geleakt (bleibt auch in
        // der Git-Historie stehen, das ist eine separate, noch offene Entscheidung). Rotieren im
        // Code loest das nur zur Haelfte: die ALTE SHA-1 bleibt in der Google-Cloud-Console-
        // Registrierung der Android-OAuth-Client-ID (siehe GoogleClientConfig.kt) gueltig, bis
        // sie dort manuell entfernt und durch die NEUE ersetzt wird - das ist Owner-Aufgabe
        // ausserhalb dieses Repositories.
        //
        // Derselbe Aussen-Mechanismus wie beim Release-Keystore darunter: der Dateipfad kommt
        // ueber debugStoreFile (Gradle-Property) herein, kein Keystore mehr im Repository.
        // storePassword/keyAlias/keyPassword bleiben die von der Android-Tooling selbst
        // verwendeten, oeffentlich bekannten Standardwerte fuer Debug-Keystores - kein Geheimnis,
        // aber ueberschreibbar per Env-Var, falls der neue Keystore andere Werte bekommen soll.
        // OHNE debugStoreFile faellt AGP auf sein eigenes, automatisch verwaltetes
        // ~/.android/debug.keystore pro Maschine zurueck: der Build bricht nicht, aber die
        // SHA-1-Stabilitaet ueber Baumaschinen hinweg (der urspruengliche Grund fuer den fest
        // eingecheckten Keystore) fehlt dann wieder, bis der neue Keystore als Secret hinterlegt
        // ist (CI) bzw. lokal verteilt wurde (andere Entwickler).
        if (debugStoreFile != null) {
            getByName("debug") {
                storeFile = debugStoreFile
                storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
            }
        }
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "tflite"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    sourceSets {
        // Robolectric liest fuer lokale Unit-Tests die zusammengefuehrten Assets des
        // debug-Build-Types (nicht die des test-Source-Sets) - daher hier statt in "test".
        getByName("debug") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
        warningsAsErrors = false
        textReport = true
        htmlReport = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Android Basis
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // Jetpack Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation(libs.material)
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room Datenbank
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // B-11: Nachfolger fuer org.tensorflow:tensorflow-lite-task-audio (abgekuendigt, nicht
    // 16-KB-seitenausgerichtet). tasks-audio zieht tasks-core mit dem eigentlichen nativen
    // Klassifikations-Code (libmediapipe_tasks_jni.so); dessen LOAD-Segmente sind 0x4000
    // (16 KB) ausgerichtet statt der 0x1000 (4 KB) der alten Bibliothek - per readelf -lW
    // gegen beide AARs geprueft.
    implementation(libs.mediapipe.tasks.audio)

    // M5: ntfy-Versand (ein HTTP-POST, kein SDK) und der Heartbeat der Totmannschaltung.
    implementation(libs.okhttp)
    // M5: Wiederholung fehlgeschlagener Versendungen und der periodische Heartbeat.
    implementation(libs.androidx.work.runtime.ktx)

    // M7b: Google-Anmeldung fuer den Drive-Sync (Plan 8.4.3). Nur die Anmeldung selbst braucht
    // ein Geraet mit echten Play-Services zur Pruefung - die Bibliotheken lassen sich ohne
    // google-services.json und ohne echte Client-ID kompilieren, ausschliesslich zur Laufzeit
    // wird eine echte OAuth-Client-ID benoetigt (siehe GoogleClientConfig).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    // M6: verschluesselte Ablage fuer ntfy-Topic/-Server und die Heartbeat-URL (Plan Abschnitt
    // 6) - EncryptedSharedPreferences, Schluessel im Android Keystore, Tink darunter.
    implementation(libs.androidx.security.crypto)

    // M11 Etappe B: Videobeweis. Bewusster Bruch mit dem sonst minimalen Abhaengigkeitsstil -
    // begruendet in docs/PROMPT_M11_FOTO_VIDEO.md B.2: Ueber den System-Kamera-Intent laesst
    // sich die Tonspur nicht abschalten, und genau das ist die Voraussetzung dafuer, dass die
    // Kamera das Mikrofon nicht anfasst und die Pegelmessung waehrend der Aufnahme durchlaeuft
    // (Owner-Entscheidung E9/V4). Auch Maximaldauer und Aufloesung waeren ueber den Intent
    // nicht verlaesslich steuerbar.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    // Diagnose & Fehleranalyse (Konzept DIAGNOSE_OBSERVABILITY_KONZEPT.md)
    implementation(libs.sentry.android)

    // Testen
    testImplementation(libs.junit)
    // Testluecken-Auftrag Stufe 2: TestListenableWorkerBuilder fuer die WorkManager-Worker.
    testImplementation(libs.androidx.work.testing)
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Prueft den ntfy-Versand gegen einen echten HTTP-Server statt gegen einen Fake-Client:
    // Nur so ist belegt, dass Pfad, Header und Rumpf tatsaechlich so rausgehen wie gedacht.
    testImplementation(libs.okhttp.mockwebserver)
    // Spike (Owner-Auftrag nach dem SettingsScreen-Scroll-Bug): Compose-UI-Tests unter
    // Robolectric statt androidTest, damit sie ohne Emulator in derselben JVM-Testsuite laufen.
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.05.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.navigation:navigation-testing:2.7.7")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Fuer Berechtigungs-Regressionstests (#129): der einzige Weg, den echten System-
    // Berechtigungsdialog im Emulator zu bedienen (Allow/Deny antippen), siehe
    // BerechtigungsTestHelfer.kt. Reines Testdependency, nicht Teil der App.
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// Praefprotokoll Frage 5 (Owner-Entscheidung vom 11.09.2026: "Pruefe das selber und ueberlege
// dir ein Verfahren, das kuenftig abzufangen und setze es um"). Der konkrete Anlass:
// LevelSampleDao.loescheVor() hatte ausformuliertes KDoc ("ohne sie waechst die Tabelle
// unbegrenzt"), wurde aber in app/src/main nirgends aufgerufen - nur vier Test-Fakes
// implementierten sie, die Suite pruefte also ein Verhalten, das es in Produktion nicht gab.
//
// Heuristik, kein vollstaendiger Analyzer: eine DAO-Methode gilt als "unbenutzt", wenn ihr Name
// als Aufruf ".methodName(" in KEINER main-Quelldatei ausser der eigenen Deklaration vorkommt.
// Das uebersieht absichtlich zwei Faelle, die false positives waeren: (1) generische, mehrfach
// gleichnamig auftretende CRUD-Namen (insert/insertAll/update/delete) - ausgeschlossen, weil ein
// Treffer auf EINER anderen DAO deren eigenen, gleichnamigen Aufruf faelschlich als Beleg fuer
// DIESE Methode werten wuerde; (2) Aufrufe ueber eine Interface-Referenz statt den konkreten
// Klassennamen. Beides sind akzeptierte false negatives (der Check uebersieht dann seltener einen
// echten Fall) - ein false positive (ein tatsaechlich benutztes Feld faelschlich als tot melden
// und damit den Build grundlos rot machen) waere der schlimmere Fehler fuer ein CI-Gate.
val checkUnusedDaoMethods =
    tasks.register("checkUnusedDaoMethods") {
        group = "verification"
        description = "Findet DAO-Methoden, die deklariert, aber nirgends in app/src/main aufgerufen werden (Praefprotokoll Frage 5)."
        doLast {
            val ignorierteNamen = setOf("insert", "insertAll", "update", "delete", "get")

            // Bereits bekannter, bewusst NICHT hier stillschweigend behobener Bestand zum Zeitpunkt
            // der Einfuehrung dieses Checks (Praefprotokoll Frage 5) - dieser Check soll KUENFTIGE
            // Faelle abfangen (Owner-Wortlaut), nicht rueckwirkend jeden bereits bestehenden Fund
            // ungefragt selbst reparieren. Jeder Eintrag ist "Datei:Methode()", damit ein gleich
            // benannter, aber ECHTER kuenftiger Fund in einer ANDEREN Datei nicht mit ausgeblendet
            // wird. Siehe Korrekturliste (Praefprotokoll-Artefakt) C-4 fuer loescheVor().
            val bekannterBestand =
                setOf(
                    // loescheVor() ist NICHT mehr hier gelistet - seit C-4 (Owner-Entscheidung vom
                    // 11.09.2026: "loescheVor() verdrahten") wird es aus DriveSyncCoordinator aufgerufen,
                    // der Check findet den Aufruf jetzt selbst.
                    "com/example/lrmprotokoll/data/LevelSampleDao.kt:anzahl", // nur fuer Tests/Diagnose gedacht, nie produktiv gebraucht
                    "com/example/lrmprotokoll/data/SessionDao.kt:anzahl", // dito
                    // fuer eine noch nicht gebaute Fehler-UI vorbereitet
                    "com/example/lrmprotokoll/data/DriveDailyFileDao.kt:letzterFehlschlag",
                    "com/example/lrmprotokoll/data/AlertDao.kt:fehlgeschlagene", // C-5 (Befund 04) - Retry-Verdrahtung noch offen
                    "com/example/lrmprotokoll/data/NoiseDao.kt:restoreMultiple", // Mehrfachauswahl im Papierkorb noch nicht gebaut
                    "com/example/lrmprotokoll/data/NoiseDao.kt:deleteMultiple", // dito
                    "com/example/lrmprotokoll/data/NoiseDao.kt:setNotes", // Notizfeld-UI noch nicht gebaut
                )

            val methodRegex = Regex("""(?:suspend\s+)?fun\s+(\w+)\s*\(""")

            val mainDir = file("src/main/java")
            val alleKtDateien = mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            val inhalte = alleKtDateien.associateWith { it.readText() }
            val daoDateien = alleKtDateien.filter { inhalte.getValue(it).contains("@Dao") }

            val unbenutzt = mutableListOf<String>()
            for (daoDatei in daoDateien) {
                val eigenerInhalt = inhalte.getValue(daoDatei)
                for (treffer in methodRegex.findAll(eigenerInhalt)) {
                    val name = treffer.groupValues[1]
                    if (name in ignorierteNamen) continue
                    val relativerPfad = daoDatei.relativeTo(mainDir).path.replace('\\', '/')
                    if ("$relativerPfad:$name" in bekannterBestand) continue
                    val aufrufMuster = ".$name("
                    val irgendwoAufgerufen =
                        inhalte.any { (andereDatei, andererInhalt) ->
                            andereDatei != daoDatei && andererInhalt.contains(aufrufMuster)
                        }
                    if (!irgendwoAufgerufen) {
                        unbenutzt += "$relativerPfad: $name()"
                    }
                }
            }

            if (unbenutzt.isNotEmpty()) {
                throw GradleException(
                    "checkUnusedDaoMethods: NEUE DAO-Methode(n) deklariert, aber nirgends in " +
                        "app/src/main aufgerufen - entweder verdrahten, entfernen, oder falls bewusst " +
                        "fuer spaeter vorbereitet, mit Begruendung zu bekannterBestand hinzufuegen " +
                        "(Ausnahmen fuer generische CRUD-Namen: $ignorierteNamen):\n" +
                        unbenutzt.joinToString("\n") { "  - $it" },
                )
            } else {
                logger.lifecycle(
                    "checkUnusedDaoMethods: keine NEUEN unbenutzten DAO-Methoden gefunden (${bekannterBestand.size} bekannte, dokumentierte Ausnahmen).",
                )
            }
        }
    }

// In denselben Lauf wie die JVM-Tests gehaengt, damit ein neuer, unbenutzter DAO-Zugriff CI
// genauso zuverlaessig rot macht wie ein fehlgeschlagener Test - ohne eine zusaetzliche,
// leicht vergessene Zeile in androidci.yml. tasks.matching{}.configureEach{} statt
// tasks.named("test") - die von AGP/Kotlin erzeugte Aggregat-Task "test" existiert zum
// Zeitpunkt dieser Zeile in der Konfigurationsphase noch nicht (tasks.named() wirft dann
// "Task with name 'test' not found"); die Lazy-API konfiguriert sie, sobald sie entsteht,
// unabhaengig vom genauen Erzeugungszeitpunkt.
tasks.matching { it.name == "test" }.configureEach {
    dependsOn(checkUnusedDaoMethods)
}

// Praefprotokoll Frage 10 (Owner-Entscheidung vom 11.09.2026: "Baue etwas, das es aus dem Code
// automatisch generiert wird"). Der Anlass: README.md pflegte Testzahlen/Konstanten von Hand
// (u.a. "784 JVM-Tests" waehrend tatsaechlich 835 liefen, ±20% Kadenz-Toleranz in der Doku
// gegen 0.5 im tatsaechlich verwendeten AppContainer-Default) - eine von Hand gepflegte Zahl
// kann veralten, ohne dass es auffaellt. docs/KENNZAHLEN.md wird deshalb NICHT von Hand
// geschrieben, sondern bei jedem Lauf aus den tatsaechlichen Quellen neu erzeugt:
// JVM-Testergebnisse aus den XML-Berichten von `test`, instrumentierte Tests durch Zaehlen der
// @Test-Annotationen im Quellcode (nicht ausgefuehrt - dieselbe verifizierte Grenze wie in
// TESTEN_EINES_PR.md: kein durchgereichter Bluetooth-/Kamera-Adapter in dieser Umgebung),
// Schema-Version/Schwellwert-Defaults/Kadenz-Toleranz per Textsuche aus den Dateien, die sie
// tatsaechlich definieren. Bewusst simple Regex-Textsuche statt AGP-API-Zugriff auf die eigene
// Konfiguration (z.B. android.defaultConfig.minSdk) - robuster gegen AGP-Versionswechsel, auf
// Kosten davon, dass eine Umformatierung der Quellzeilen (z.B. "minSdk=29" ohne Leerzeichen)
// das jeweilige Muster verfehlen wuerde; ein Fehlschlag zeigt sich dann als "?" im generierten
// Dokument, nicht als falscher Wert.
val generiereKennzahlen =
    tasks.register("generiereKennzahlen") {
        group = "documentation"
        description = "Schreibt docs/KENNZAHLEN.md aus tatsaechlich gemessenen/gelesenen Werten (Praefprotokoll Frage 10)."
        dependsOn("test")
        doLast {
            fun ersteZahl(
                text: String,
                muster: String,
            ): String = Regex(muster).find(text)?.groupValues?.get(1) ?: "?"

            var jvmTests = 0
            var jvmFailures = 0
            var jvmErrors = 0
            var jvmDateien = 0
            val testResultsDir = file("build/test-results/testDebugUnitTest")
            testResultsDir.listFiles { f -> f.extension == "xml" }?.forEach { xmlFile ->
                jvmDateien++
                val text = xmlFile.readText()
                jvmTests += ersteZahl(text, """tests="(\d+)"""").toIntOrNull() ?: 0
                jvmFailures += ersteZahl(text, """failures="(\d+)"""").toIntOrNull() ?: 0
                jvmErrors += ersteZahl(text, """errors="(\d+)"""").toIntOrNull() ?: 0
            }

            val androidTestRoot = file("src/androidTest/java")
            var androidTests = 0
            var androidTestDateien = 0
            androidTestRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { datei ->
                androidTestDateien++
                androidTests += Regex("""@Test\b""").findAll(datei.readText()).count()
            }

            val appDatabaseText = file("src/main/java/com/example/lrmprotokoll/data/AppDatabase.kt").readText()
            val schemaVersion = ersteZahl(appDatabaseText, """version\s*=\s*(\d+),""")

            val appContainerText = file("src/main/java/com/example/lrmprotokoll/AppContainer.kt").readText()
            val kadenzToleranz = ersteZahl(appContainerText, """cadenceTolerance\s*=\s*([\d.]+),""")

            val settingsManagerText = file("src/main/java/com/example/lrmprotokoll/data/SettingsManager.kt").readText()
            val dbThresholdDefault = ersteZahl(settingsManagerText, """"db_threshold",\s*([\d.]+)f\)""")
            val meterDbThresholdDefault = ersteZahl(settingsManagerText, """"meter_db_threshold",\s*([\d.]+)f\)""")

            val buildFileText = buildFile.readText()
            val minSdk = ersteZahl(buildFileText, """minSdk\s*=\s*(\d+)""")
            val compileSdk = ersteZahl(buildFileText, """compileSdk\s*=\s*(\d+)""")

            val zeitstempel = Date().toString()
            val inhalt =
                buildString {
                    appendLine("# Kennzahlen (automatisch generiert)")
                    appendLine()
                    appendLine("**Nicht von Hand pflegen und nicht von Hand editieren.** Diese Datei entsteht durch")
                    appendLine("`./gradlew generiereKennzahlen` aus den tatsächlichen Quellen (Testergebnisse, Quellcode) -")
                    appendLine("siehe Kommentar über der Gradle-Task in `app/build.gradle.kts` (Prüfprotokoll Frage 10,")
                    appendLine("Owner-Entscheidung vom 11.09.2026: „Baue etwas, das es aus dem Code automatisch generiert")
                    appendLine("wird“, Anlass: veraltete Testzahlen und ein falscher Kadenz-Toleranz-Wert in README.md).")
                    appendLine()
                    appendLine("Generiert am: $zeitstempel")
                    appendLine()
                    appendLine("## Tests")
                    appendLine(
                        "- JVM-/Robolectric-Tests: **$jvmTests** (aus $jvmDateien XML-Berichten unter `build/test-results/testDebugUnitTest`) · $jvmFailures Failures, $jvmErrors Errors",
                    )
                    appendLine(
                        "- Instrumentierte Tests (Emulator): **$androidTests** `@Test`-Annotationen in $androidTestDateien Dateien unter `src/androidTest`",
                    )
                    appendLine(
                        "  - Gezählt aus dem Quellcode, NICHT hier ausgeführt (kein durchgereichter Bluetooth-/Kamera-Adapter in dieser Umgebung, siehe `docs/TESTEN_EINES_PR.md`).",
                    )
                    appendLine()
                    appendLine("## Datenbank")
                    appendLine("- Room-Schemaversion: **$schemaVersion** (`AppDatabase.kt`)")
                    appendLine()
                    appendLine("## BLE / Kadenz")
                    appendLine(
                        "- Tatsächlich verwendete Kadenz-Toleranz (`AppContainer.cadenceTolerance`): **±${(
                            kadenzToleranz.toDoubleOrNull()?.times(
                                100,
                            )
                        )?.let { "%.0f".format(it) } ?: "?"}%**",
                    )
                    appendLine("  - Der `ConnectionSupervisor`-Klassen-Default (nur in Tests aktiv) ist ±20% - siehe dessen KDoc.")
                    appendLine()
                    appendLine("## Schwellwerte (Trigger-Defaults)")
                    appendLine("- Mikrofon-Schwelle (`db_threshold`): **$dbThresholdDefault dB**")
                    appendLine("- Messgerät-Schwelle (`meter_db_threshold`): **$meterDbThresholdDefault dBA**")
                    appendLine()
                    appendLine("## Build")
                    appendLine("- minSdk: **$minSdk** · compileSdk: **$compileSdk**")
                }
            file("$rootDir/docs/KENNZAHLEN.md").apply {
                parentFile.mkdirs()
                writeText(inhalt)
            }
            logger.lifecycle(
                "generiereKennzahlen: docs/KENNZAHLEN.md geschrieben ($jvmTests JVM-Tests, $androidTests instrumentierte Tests gezaehlt).",
            )
        }
    }

// Testluecken-Auftrag Stufe 1: Kover misst die Line-Coverage, damit die weiteren Stufen gegen
// eine echte Zahl arbeiten koennen statt zu schaetzen. HTML fuers Durchklicken lokal, XML als
// maschinenlesbare Grundlage fuer den CI-Summary-Schritt (siehe androidci.yml).
kover {
    reports {
        total {
            html {
                onCheck = false
            }
            xml {
                onCheck = false
            }
        }
        filters {
            excludes {
                // Generierter Code ohne eigene Logik - taeuschte sonst eine falsche Coverage vor,
                // ohne dass ein Test dagegen ueberhaupt sinnvoll waere.
                classes("*.BuildConfig", "*_Impl", "*_Impl\$*")
            }
        }
    }
}

// Testluecken-Auftrag Stufe 1: android.set(true) passt u.a. die Import-Reihenfolge an das in
// Android-Projekten uebliche Schema an. Wildcard-Importe (import ...*) sind im Bestand
// durchgaengiger, bewusster Stil (siehe MainActivity.kt etc.) - die Regel dagegen bleibt
// deshalb ueber .editorconfig deaktiviert statt den ganzen Bestand umzuschreiben.
ktlint {
    android.set(true)
}
