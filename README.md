# Tono — ringtone maker

Android app: search YouTube / YouTube Music (or paste a link, or pick a file), trim a clip,
and set it as your ringtone, notification sound or alarm.

> **Personal project.** I built Tono for my own use, with [Claude](https://claude.ai). I haven't
> read a single line of the code, so I can't vouch for how it works.
> It's shared as is: I'm not planning to maintain it for others, and I won't be accepting issues
> or pull requests. You're welcome to fork it.

## Stack

| Piece | Used for |
| --- | --- |
| Kotlin + Jetpack Compose (Material 3) | UI |
| [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) | YouTube search and audio stream URLs, no API key |
| OkHttp | HTTP for NewPipe + chunked audio download |
| Media3 ExoPlayer | Preview playback |
| Media3 Transformer + custom `AudioProcessor` | Clip, fade in/out, volume leveling, AAC `.m4a` export |
| MediaStore + RingtoneManager | Save to `Ringtones/Tono`, `Notifications/Tono`, `Alarms/Tono` and set the default |
| DataStore | Filters and theme |
| WorkManager | Ringtone rotation |
| GitHub Releases API + FileProvider | In-app update check and install |

Build: AGP 9.5 (built-in Kotlin), Gradle 9.6 (wrapper), compileSdk 37, minSdk 29 (Android 10+).

## Setup

1. Install Android Studio (bundles the JDK and SDK): `brew install --cask android-studio`
2. Open it once, finish the setup wizard, and in **SDK Manager** install
   *Android SDK Platform 37* and *Build-Tools 36.0.0*.
3. Open this folder in Android Studio (or create `local.properties` with
   `sdk.dir=/Users/<you>/Library/Android/sdk`).

## Run on your phone

1. On the phone: **Settings › About phone**, tap **Build number** 7 times, then
   **Developer options › USB debugging** on. Plug in and tap **Allow**.
2. `./gradlew installDebug`
3. `adb shell am start -n app.tono/.MainActivity`

Logs: `adb logcat --pid=$(adb shell pidof app.tono)`

## Tests

`./gradlew test` runs the JVM unit tests (filter rules, chorus detection, leveling, fades,
rotation order, version comparison).

## Release build

`./gradlew :app:assembleRelease` signs with the key in `tono-release.jks`, using the passwords in
`keystore.properties`. Neither file is committed. Back both up: Android only installs updates
signed with the same key, so losing them means uninstalling (and losing app data) to update.
Output: `app/build/outputs/apk/release/app-release.apk`.

## In-app updates

**Settings › Updates** checks the latest GitHub release (also once a day when the app opens; can be
turned off). If it's newer, Tono shows the notes, downloads the APK, checks its SHA-256 and that it's
Tono signed with the same key, then opens Android's installer. The first time, Android asks to allow
"Install unknown apps" for Tono; Play Protect may also ask to scan an app it hasn't seen
(**More details › Install without scanning** skips that).
