buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9.4 brings KGP 2.2.10 transitively; pin the newer Kotlin toolchain
        // so the Compose compiler plugin version matches the language version.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
        classpath("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}
