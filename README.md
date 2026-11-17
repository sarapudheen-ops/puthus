# bod3

Android app built with Kotlin and Jetpack Compose.

| | |
|---|---|
| Package | `com.bod3.app` |
| minSdk / targetSdk | 24 / 35 |
| Gradle | 8.9 (wrapper) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |

## Building

Requires JDK 17 and the Android SDK (set `ANDROID_HOME` or create `local.properties` with `sdk.dir=...`).

```sh
./gradlew assembleDebug   # APK -> app/build/outputs/apk/debug/
./gradlew lintDebug       # report -> app/build/reports/
```

## CI

`.github/workflows/android.yml` runs `assembleDebug lintDebug` on every push and pull request,
and uploads the debug APK and build reports as workflow artifacts.
