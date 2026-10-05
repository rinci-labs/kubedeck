# Building and running Kube

## Toolchain

| Component | Version | Where it is pinned |
| --- | --- | --- |
| Android Gradle Plugin | 9.4.1 | `build.gradle.kts` (`plugins { id("com.android.application") version "9.4.1" }`) |
| Gradle | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` (`distributionUrl`) |
| Kotlin | 2.4.20 | `build.gradle.kts` root `buildscript` classpath and plugin versions |
| Compose compiler plugin | 2.4.20 (`kotlin-compose-compiler-plugin-embeddable`) | `build.gradle.kts` root `buildscript` |
| Kotlin serialization plugin | 2.4.20 | `build.gradle.kts` root `plugins` |
| Compose BOM | 2026.09.00 | `app/build.gradle.kts` |
| compileSdk | 37 | `app/build.gradle.kts` |
| targetSdk | 37 | `app/build.gradle.kts` |
| minSdk | 26 | `app/build.gradle.kts` |
| Java source/target | 17 | `app/build.gradle.kts` (`compileOptions` and `kotlin.compilerOptions.jvmTarget`) |
| app version | `versionName` from `-PappVersion=X.Y.Z` (default `0.1.0`), `versionCode` = X·10000 + Y·100 + Z (min 1) | `app/build.gradle.kts`; release CI sets it from the `vX.Y.Z` tag |

Gradle Java toolchain: the project sets `sourceCompatibility`/`targetCompatibility` to 17 and
`jvmTarget` to 17, so a JDK 17 or newer must be the one Gradle runs on.

Key libraries (`app/build.gradle.kts`): `androidx.core:core-ktx:1.19.1`,
`androidx.activity:activity-compose:1.13.0`, `androidx.navigation:navigation-compose:2.10.2`,
`androidx.lifecycle:*:2.11.0`, `androidx.datastore:datastore-preferences:1.2.1`,
`com.squareup.okhttp3:okhttp:5.5.0`, `kotlinx-coroutines-android:1.11.0`,
`kotlinx-serialization-json:1.11.0`, `org.yaml:snakeyaml:2.7`, and Compose Material 3 plus
`material-icons-extended:1.7.8`. The Compose UI/Foundation/Material 3 artifacts are unversioned
because the BOM supplies their versions.

## Gradle wrapper

Use the wrapper. It pins Gradle **9.7.1** and needs no local Gradle install:

| File | Purpose |
| --- | --- |
| `gradlew` | POSIX launcher (`sh gradlew …`, or `./gradlew …` on a POSIX host) |
| `gradlew.bat` | Windows launcher (`gradlew.bat …`) |
| `gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` = `https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip`, `distributionBase`/`zipStoreBase` = `GRADLE_USER_HOME`, `distributionPath`/`zipStorePath` = `wrapper/dists`, `networkTimeout=10000`, `validateDistributionUrl=true` |
| `gradle/wrapper/gradle-wrapper.jar` | wrapper bootstrapper (the standard `org.gradle.wrapper.GradleWrapperMain` jar shipped inside the Gradle 9.7.1 distribution, taken verbatim — it is not hand-written) |

The properties also carry a `distributionSha256Sum` of
`acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`, the official SHA-256 of
`gradle-9.7.1-bin.zip` as published at
`https://services.gradle.org/distributions/gradle-9.7.1-bin.zip.sha256`, so a tampered download
fails loudly instead of being executed.

The wrapper scripts come from the Gradle repository at the `v9.7.1` tag, so they are the canonical
generated scripts for this version rather than a reconstruction. Verify them with:

```bash
sh gradlew --version        # POSIX
gradlew.bat --version       # Windows cmd
```

Both print `Gradle 9.7.1`.

**Fallback:** if you would rather not use the wrapper, a locally installed Gradle 9.7.1 on `PATH`
works equally well — every command below is shown once and is valid with either launcher. There is
no longer any requirement to have Gradle installed to build this project.

## `local.properties` and `sdk.dir`

`local.properties` is machine-specific and is git-ignored (`.gitignore` lists both `/local.properties`
and `local.properties`). It must contain the SDK location:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

The double backslashes and the `C\:` escape are required — this is a Java properties file. On
Linux/macOS use `/home/<you>/Android/Sdk` instead. Point `sdk.dir` at an SDK that has **platform 37
installed**; see the next section.

## Serve the right SDK platform

`compileSdk = 37` means the build needs Android platform 37 in the SDK. Install it with:

```bash
sdkmanager "platforms;android-37"
```

A missing platform fails configuration with an error naming `compileSdk 37`. AGP selects the
build-tools revision it needs; installing the platform is the requirement this project creates.

## Debug build

```bash
./gradlew :app:assembleDebug
```

On Windows:

```bat
gradlew.bat :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The debug build adds the application id suffix `.debug` (`applicationIdSuffix = ".debug"`), so the
debug package is `dev.rafa.kubemobile.debug` and it can be installed alongside the release build.

## Install with adb

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch it:

```bash
adb shell am start -n dev.rafa.kubemobile.debug/dev.rafa.kubemobile.MainActivity
```

## Release build

```bash
./gradlew :app:assembleRelease
```

On Windows:

```bat
gradlew.bat :app:assembleRelease
```

APK output:

```text
app/build/outputs/apk/release/app-release.apk
```

The release build is minified and resource-shrunk
(`isMinifyEnabled = true`, `isShrinkResources = true`) using
`getDefaultProguardFile("proguard-android-optimize.txt")` plus `app/proguard-rules.pro`.

**Signing:** the release build is signed with the *debug* keystore
(`signingConfig = signingConfigs.getByName("debug")`). This is deliberate — it keeps the artifact
installable without a user-provided keystore — but it is not suitable for distribution: replace the
signing config with your own keystore before publishing.

Install it:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n dev.rafa.kubemobile/dev.rafa.kubemobile.MainActivity
```

Because R8 shrinks the release artifact, verify any behaviour change on the debug build first; see
the limitations section of [../README.md](../README.md).

## Other useful tasks

```bash
./gradlew :app:assembleDebug --stacktrace   # full failure detail
./gradlew :app:clean :app:assembleDebug     # clean rebuild
./gradlew :app:lintDebug                    # lint (abortOnError = false, so findings do not fail the build)
./gradlew projects                          # confirm the single :app module
```

Substitute `gradlew.bat` for `./gradlew` on Windows, or use a locally installed `gradle` 9.7.1
instead — the tasks are identical.

`lint { abortOnError = false }` is set in `app/build.gradle.kts`, so lint findings are reported but
do not fail the build.

## AGP 9 caveats this project hits

These are the non-obvious things that break a build if you follow generic Android instructions.

### Built-in Kotlin — do not apply a Kotlin Android plugin

`app/build.gradle.kts` applies exactly three plugins:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
```

There is **no `org.jetbrains.kotlin.android` plugin**, and the root `build.gradle.kts` declares no
`kotlin-android` plugin. AGP 9 provides Kotlin compilation itself. Adding
`id("org.jetbrains.kotlin.android")` will conflict with the built-in Kotlin support; the Kotlin
Gradle plugin is present only as a *classpath* dependency.

### Kotlin toolchain is pinned in the root `buildscript`

AGP 9.4.1 pulls in Kotlin Gradle Plugin **2.2.10** transitively, which does not match the Compose
compiler plugin this project uses. The root `build.gradle.kts` therefore pins the newer toolchain
explicitly:

```kotlin
buildscript {
    repositories { google(); mavenCentral() }
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
```

If you bump AGP, keep the Kotlin classpath and the two Kotlin plugin versions in step with each
other. Do not remove the `buildscript` block on the assumption that the `plugins` block covers it.

### Kotlin DSL configuration lives inside the `android` block

This project uses the AGP 9 `android { kotlin { compilerOptions { … } } }` form, not the older
top-level `kotlinOptions`:

```kotlin
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
}
```

### `compileSdk = 37`

Platform 37 must be installed (above). `targetSdk = 37` also opts into the platform's behaviour
changes at runtime.

### Other settings worth knowing

- `resourceConfigurations += listOf("en")` — only English resources are packaged; `strings.xml` is
  the only locale.
- `packaging.resources.excludes` drops `META-INF/{AL2.0,LGPL2.1}`,
  `META-INF/versions/9/OSGI-INF/MANIFEST.MF`, `META-INF/*.kotlin_module`,
  `kotlin-tooling-metadata.json` and `DebugProbesKt.bin`.
- `gradle.properties` enables the configuration cache
  (`org.gradle.configuration-cache=true`) and the build cache
  (`org.gradle.caching=true`), with `org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g` and
  `org.gradle.parallel=true`. If you change build logic and hit a configuration-cache problem, add
  `--no-configuration-cache` to the command to confirm.
- `android.useAndroidX=true`, `android.nonTransitiveRClass=true`, `android.nonFinalResIds=true`.
- The manifest sets `android:allowBackup="false"`, `android:usesCleartextTraffic="false"`, and
  declares only `INTERNET` and `ACCESS_NETWORK_STATE`.
