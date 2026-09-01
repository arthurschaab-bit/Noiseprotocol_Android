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
        // Fest eingecheckter Debug-Keystore statt des von AGP pro Maschine automatisch neu
        // erzeugten ~/.android/debug.keystore: die Google-Cloud-Console-Registrierung der
        // Android-OAuth-Client-ID (siehe GoogleClientConfig.kt) braucht einen SHA-1-
        // Fingerabdruck, der ueber alle Baumaschinen hinweg (lokal, diese Sandbox, GitHub
        // Actions) STABIL bleibt - sonst funktioniert "Mit Google verbinden" nur zufaellig auf
        // genau der Maschine, die den zuletzt registrierten Fingerabdruck erzeugt hat. Passwort/
        // Alias/Schluesselpasswort sind die von der Android-Tooling selbst verwendeten,
        // oeffentlich bekannten Standardwerte fuer Debug-Keystores - kein Geheimnis, das hier
        // preisgegeben wuerde.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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

    // Diagnose & Fehleranalyse (Konzept DIAGNOSE_OBSERVABILITY_KONZEPT.md)
    implementation(libs.sentry.android)

    // Testen
    testImplementation(libs.junit)
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
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.05.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.navigation:navigation-testing:2.7.7")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
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
