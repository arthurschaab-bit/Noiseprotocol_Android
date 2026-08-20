plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.example.lrmprotokoll"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.lrmprotokoll"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Android Basis
    implementation(libs.androidx.core.ktx)
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

    // Testen
    testImplementation(libs.junit)
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Prueft den ntfy-Versand gegen einen echten HTTP-Server statt gegen einen Fake-Client:
    // Nur so ist belegt, dass Pfad, Header und Rumpf tatsaechlich so rausgehen wie gedacht.
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
