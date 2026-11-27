# Building and installing aodkit

Two scripts in `scripts/` build the app and install it on a phone. Each comes in a PowerShell
version (`.ps1`, for Windows) and a bash version (`.sh`, for Git Bash, macOS and Linux). They behave
the same way.

## Prerequisites

- **JDK 17 or newer** (the project is built with JDK 21).
- **Android SDK** with platform `android-37` and platform-tools (for `adb`).
- **`local.properties`** at the repo root pointing to the SDK. It is git-ignored, so a fresh clone
  needs one, e.g. `sdk.dir=C\:\\android\\sdk` on Windows. Android Studio creates it on first sync.
- **A phone with USB debugging enabled**, for installing.

Gradle itself does not need installing: the scripts use the Gradle wrapper (`gradlew`), which
downloads the right version on first run.

## Build

```powershell
.\scripts\build.ps1                  # everything: debug APK, release APK, release AAB
.\scripts\build.ps1 -Target debug    # just one: debug, release or aab
```

```bash
scripts/build.sh                     # everything
scripts/build.sh debug               # just one: debug, release or aab
```

When it finishes, the script lists the files it built:

| Target | Output | Use |
|---|---|---|
| `debug` | `app/build/outputs/apk/debug/app-debug.apk` | Installing on your own phone while developing |
| `release` | `app/build/outputs/apk/release/app-release.apk` | Installing a release build directly |
| `aab` | `app/build/outputs/bundle/release/app-release.aab` | Uploading to Google Play |

Without release signing (see below), the release APK is named `app-release-unsigned.apk` and the
script prints a warning.

## Release signing

Release builds are signed only when `keystore.properties` exists at the repo root. Without it they
are built unsigned: an unsigned APK cannot be installed, and Play rejects an unsigned AAB.

Set it up once:

1. Create the key:

   ```bash
   keytool -genkeypair -v -keystore aodkit-release.jks -alias aodkit -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Create `keystore.properties` at the repo root:

   ```properties
   storeFile=aodkit-release.jks
   storePassword=...
   keyAlias=aodkit
   keyPassword=...
   ```

`keystore.properties`, `*.jks` and `*.keystore` are git-ignored. Back up the `.jks` file and its
passwords somewhere safe. They are the app's identity on Google Play: if they are lost, the app can
never be updated under the same signature.

## Install on the phone

```powershell
.\scripts\install.ps1                          # install the debug APK
.\scripts\install.ps1 -Build                   # build it first, then install
.\scripts\install.ps1 -Variant release -Build  # release build (needs signing)
```

```bash
scripts/install.sh                   # install the debug APK
scripts/install.sh --build           # build it first, then install
scripts/install.sh release --build   # release build (needs signing)
```

The script replaces the installed app and keeps its data, so aodkit's settings and granted
permissions survive. It finds `adb` by checking, in order:

1. `ANDROID_HOME`
2. `ANDROID_SDK_ROOT`
3. `sdk.dir` in `local.properties`
4. `adb` on the `PATH`

Reinstalling closes the AOD Screen if it is showing. Turn the screen off once to bring it back.

## Troubleshooting

- **"The release APK is unsigned"**: set up `keystore.properties` (see [Release signing](#release-signing))
  and rebuild.
- **"signed with a different key (debug vs release)"**: debug and release builds use different
  keys, and Android won't replace one with the other. Run `adb uninstall app.aodkit` first. This
  wipes aodkit's settings and permissions, so they have to be granted again in the app.
- **"No phone connected"**: check the cable and that USB debugging is on, and accept the
  "Allow USB debugging" prompt on the phone. `adb devices` should list the phone as `device`, not
  `unauthorized`.
- **"Several devices are connected"**: choose one with `ANDROID_SERIAL`, e.g.
  `$env:ANDROID_SERIAL = 'RZCY92DPVJK'` in PowerShell or `export ANDROID_SERIAL=RZCY92DPVJK` in bash.
- **PowerShell refuses to run the script** ("running scripts is disabled on this system"): run it
  as `powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1`, or allow local scripts once
  with `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.
- **`Permission denied` running a `.sh` script**: run it through bash (`bash scripts/build.sh`).
- **Help text**: `Get-Help .\scripts\build.ps1 -Full`, or `scripts/build.sh --help`.
