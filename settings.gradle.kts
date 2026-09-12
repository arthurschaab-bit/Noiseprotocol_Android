pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Chaquopy (Schritt 1 des Bericht-Umbaus, embedded CPython fuer den geplanten
        // High-End-Bericht): das Plugin selbst kommt ueber mavenCentral(), aber die
        // Python-Distributionen und die Runtime-AARs liegen nur in Chaquopys eigenem Repository.
        // FAIL_ON_PROJECT_REPOS verbietet ein repositories{} im Modul selbst, deshalb hier zentral.
        maven("https://chaquo.com/maven")
    }
}

rootProject.name = "Lrmprotokoll"
include(":app")
 