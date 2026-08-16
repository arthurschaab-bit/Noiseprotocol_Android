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

    implementation("org.tensorflow:tensorflow-lite-task-audio:0.4.4")

    // Testen
    testImplementation(libs.junit)
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
