pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
}

// Ein Quellcode (src/), mehrere Minecraft-Versionen. Je Version gibt es versions/<version>/gradle.properties.
// Neue Version: hier eintragen + Ordner mit gradle.properties anlegen.
stonecutter {
    create(rootProject) {
        versions("1.21.11", "26.3")
        vcsVersion = "26.3"
    }
}

rootProject.name = "NovaClient"
