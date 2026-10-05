import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Release CI passes -PappVersion=X.Y.Z from the pushed vX.Y.Z tag; local builds use the default.
val appVersion = (findProperty("appVersion") as String?) ?: "0.1.0"
val appVersionCode = appVersion.split(".").let { parts ->
    require(parts.size == 3 && parts.all { it.toIntOrNull() != null }) { "appVersion must be X.Y.Z, got $appVersion" }
    // 0.1.0 shipped as versionCode 1; X*10000 + Y*100 + Z keeps every later tag above it.
    maxOf(1, parts[0].toInt() * 10000 + parts[1].toInt() * 100 + parts[2].toInt())
}
val releaseKeystore = System.getenv("KUBEDECK_KEYSTORE_FILE")?.let(::file)?.takeIf { it.isFile }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.rafa.kubemobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.rafa.kubemobile"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KUBEDECK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KUBEDECK_KEY_ALIAS")
                keyPassword = System.getenv("KUBEDECK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Published releases are signed with the keystore from KUBEDECK_KEYSTORE_*
            // (the key v0.1.0 shipped with), so installs and in-app updates keep the
            // same certificate. Without it, local builds fall back to the debug key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/*.kotlin_module",
            "kotlin-tooling-metadata.json",
            "DebugProbesKt.bin",
        )
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.yaml:snakeyaml:2.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
