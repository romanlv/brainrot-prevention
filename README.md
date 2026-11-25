# Brainrot Prevention

A local-only app and website blocker for Android. No ads, no account, no analytics, and no network permission at all:
nothing leaves the phone.

Kotlin + Jetpack Compose, Android 9+ (minSdk 28; Android 10+ recommended), tested on Android 17 (API 37).

## What it does

- **Schedules.** Block a list of apps and websites, or allow only a list.
  - A schedule can run by time of day and weekday, while you're on a given Wi-Fi network, or while you're at a place.
  - It can also run once a usage budget runs out: minutes of use or number of opens, per day or per hour.
- **Quick Block.** One-tap focus timer from the home screen.
- **Website blocking.** Reads the address bar of supported browsers: Chrome, Firefox, Edge, Brave, Samsung Internet,
  Opera, DuckDuckGo, Vivaldi and a few more. Rules match by domain or by keyword. Browsers it can't read are blocked
  as apps while a schedule has website rules.
- **Notifications.** Optionally hides notifications from blocked apps and pauses their media.
- **Strict Mode.** Makes blocking hard to undo until a condition is met: a timer, the schedules ending, plugging in a
  charger, a PIN someone else holds, or a cooldown you have to wait out.
  - Optionally blocks the Settings app and uninstalling while it's on.
  - One emergency exit per 30 days, behind a typed pledge and a 60-second countdown.
- **Experimental: "X: keep Home on Following."** When X (Twitter) opens its Home on "For you", the app taps
  "Following" for you. You keep the people you follow, without the recommendation feed. Search, profiles and
  messages are left alone. It's built from the screen layout of X 12.44; if an X update changes that layout, the
  feature simply stops doing anything. Turn it on under Settings → Experimental.
- **Backup.** Export and import the whole configuration as JSON.

### How it works

An accessibility service watches which app is in front and reads browser address bars. When something is blocked, it
sends you to the home screen and shows a block screen. A pure-Kotlin engine (`core:engine`) makes every blocking
decision from a snapshot of the configuration, so the rules are unit-tested without a device.

### Limits

Android lets an app close only so many doors. Safe mode, ADB, a factory reset, and disabling the accessibility
service from a computer all still work. The app lists these under Settings → About → "How strong is this?". Known
gaps and design deviations are tracked in [`docs/DEVIATIONS.md`](docs/DEVIATIONS.md).

## Install

There's no Play Store build, so you need to sideload it.

**[⬇ Download the latest APK](https://github.com/romanlv/brainrot-prevention/releases/latest)**. Each release lists
the APK's SHA-256 and the signing certificate's fingerprint. Every release is signed with the same key, so updates
install over the old version and keep your settings. To get updates automatically, add this repository's URL to
[Obtainium](https://github.com/ImranR98/Obtainium).

1. Open the release page on the phone, download the `.apk` and open it. Android asks once to allow installs from
   your browser. Or install from a computer with `adb install brainrot-prevention-<version>.apk`, or build your own
   (see below).
2. Open the app. The setup checklist asks for four things:
   - **Accessibility service.** Required. On Android 13+ the toggle is greyed out for sideloaded apps ("Restricted
     setting"). Go to **Settings → Apps → Brainrot Prevention → ⋮ → Allow restricted settings**, then turn the service
     on. The ⋮ menu item only appears after you've tried the toggle once.
   - **Usage access.** Required.
   - **Notification access.** Optional. It hides notifications from blocked apps.
   - **Notifications.** Shows what's blocking and warns before a budget runs out.
3. Pick a template or start from scratch, choose the apps and sites, then tap **Save**.

Tips:

- **Start Strict Mode with a short timer** until you trust your setup.
- **Strict Mode won't start while the app is on an accessibility shortcut** (the floating button, volume keys and so
  on), because the shortcut could switch blocking off in one tap.
- **Run only one accessibility-based blocker at a time.** Two blockers fight over the screen.

## Build

Needs JDK 25 and the Android SDK (command-line tools, platform 37, build-tools 37). `scripts/doctor.sh` checks the
toolchain.

```sh
./gradlew :app:assembleDebug          # debug build (has test hooks; don't use it day to day)
scripts/release.sh                    # signed release APK into dist/
```

`scripts/release.sh` needs your own signing key in `keystore/` (gitignored):

```properties
# keystore/keystore.properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Create the key with
`keytool -genkeypair -v -keystore keystore/release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000`.
**Back it up.** Updates must be signed with the same key, or Android refuses them and you'd have to uninstall,
which loses your settings (export a backup first).

## Tests

```sh
scripts/verify.sh            # G0–G2 on the host: lint + ktlint, engine tests with coverage, data/platform/app unit tests
scripts/verify.sh --device   # + G3 instrumentation tests and G4 end-to-end scenarios on an emulator
```

- The device gates boot an emulator from `scripts/emu.sh` (the AVD is created on first use). `BRP_AVD=brp37u` picks
  a Google Play `user` image, which behaves like a real phone; the default `brp37` image is debuggable.
- Always hand-check the release APK on the `user` image too: the automated suites install debuggable builds, and
  Android grants those things it refuses ordinary apps.
- `scripts/bin/adb` is a guard that refuses to talk to physical devices, so tests can't touch a real phone by
  accident.
- `report.json` under `build/verify/` is the machine-readable result.
- The end-to-end scenarios drive small fixture apps (`fixture-apps/`: a target app and two fake browsers) through
  an instrumentation driver (`e2e-driver/`).

## Layout

| Module | What's in it |
|---|---|
| `core:model` | Plain data: schedules, rules, snapshot, Strict options |
| `core:engine` | Pure decision logic: schedules, budgets, URL matching, Strict policy |
| `core:data` | Room database, DataStore prefs, repositories, backup |
| `platform` | Accessibility service, enforcement and block overlay, sensors, alarms, notifications, Strict activation |
| `app` | Compose UI |
| `testing:fixtures`, `fixture-apps`, `e2e-driver` | Test fixtures, fixture apps, end-to-end driver |

Code comments cite sections (`§5.4`, `§10.2` …) of the design spec the app was built from. The spec isn't part of
this repository; [`docs/DEVIATIONS.md`](docs/DEVIATIONS.md) records where the implementation differs from it and why.
[`AGENTS.md`](AGENTS.md) is the working loop for coding agents on this repo.

## Status

A personal project, used daily on a Pixel 9a. Version 1.0.4. Issues and pull requests are welcome.

This is an independent app. It isn't affiliated with, or endorsed by, any other blocker app or by X Corp.

## License

[MIT](LICENSE)
