# Brainrot Prevention

A local-only app and website blocker for Android (10+ recommended; minSdk 28). No ads, no accounts, no network
permission — nothing leaves the phone. Spec: `../SPEC.md`. Deviations from it: `docs/DEVIATIONS.md`.

## Install on a phone

The signed release APK is `app/build/outputs/apk/release/app-release.apk`. A copy is in `dist/` after
`scripts/release.sh`.

1. Copy the APK to the phone, or install it from the Mac with
   `$ANDROID_HOME/platform-tools/adb -s <serial> install dist/brainrot-prevention-1.0.0.apk`.
   The repo's `scripts/bin/adb` guard refuses physical devices on purpose, so use the SDK adb directly for this step.
2. Open the app. The setup checklist asks for four things:
   - **Accessibility service.** This one is required. Because the app is sideloaded, Android 13+ greys the toggle out
     ("Restricted setting"). Go to **Settings → Apps → Brainrot Prevention → ⋮ → Allow restricted settings**, then
     turn the service on. The menu item only appears after you've tried the toggle once.
   - **Usage access.** Required.
   - **Notification access.** Optional. It hides notifications from blocked apps and pauses their media.
   - **Notifications.** Shows what's blocking and warns you before a budget runs out.
3. Pick a template, or start from scratch, choose the apps and sites, then tap **Save**.

**If AppBlock is still installed and running:** two accessibility blockers will both send blocked apps home and show
their own screens. Turn off AppBlock's schedules (or its accessibility service) before relying on this app.

## First things worth knowing

- **Quick Block** (Home): pick the apps and sites with **Edit**, choose a timer, then **Start**.
- **Pause** a schedule from its ⋮ menu. The block screen also offers a pause of up to 15 minutes, after 10 seconds.
- **Strict Mode** (Strict tab) makes blocking hard to undo until its condition is met: a timer, the schedules
  ending, plugging in a charger, a PIN someone else holds, or a cooldown.
  - **Start with a short timer.**
  - With "Block Settings" on, the Settings app is unreachable while Strict Mode is active.
  - With "Block uninstalling" on, the app can't be uninstalled while Strict Mode is active.
  - The only way out early is **Emergency** (bottom of the Strict screen): once per 30 days, with a typed pledge and a
    60-second countdown.
  - Settings → About → "How strong is this?" lists the bypasses Android doesn't let an app close, such as safe mode,
    ADB and factory reset.
- **Website blocking** works by reading the address bar of supported browsers: Chrome, Firefox, Edge, Brave, Samsung
  Internet, Opera, DuckDuckGo, Vivaldi and a few more. Browsers it can't read are blocked as apps while a schedule has
  website rules. To allow one, use "Not a browser? Allow" on its block screen (not available while Strict Mode is on).
- **Backup:** Settings → Backup exports and imports the configuration as JSON.

## Build and verify (for agents)

Read `AGENTS.md` first. In short:

```
scripts/doctor.sh                 # toolchain check
scripts/verify.sh                 # host gates G0–G2 (lint/ktlint, engine tests + coverage, data tests)
scripts/verify.sh --device        # + G3 instrumentation and G4 e2e scenarios S1–S16 on the brp37 emulator
scripts/release.sh                # signed release APK into dist/ (needs keystore/, see below)
```

The release key lives in `keystore/` (gitignored: `release.jks` + `keystore.properties`). **Back it up.** Updates
must be signed with the same key, or the phone will refuse them and you'd have to uninstall first, losing settings
(export a backup before you do that).

## Status

- All gates pass on the API 37 emulator (G0–G4, 98 engine tests, 35 data tests, 49 instrumentation tests,
  16 end-to-end scenarios).
- The release build was also hand-checked on the emulator: onboarding, creating a schedule, blocking an app, and
  blocking a site in real Chrome.
- Not yet tried on a physical phone.
- Open items are listed in `docs/DEVIATIONS.md` under "Open": WebAPK detection, external displays, a location
  fallback without Play Services, and a few device-only checks.
