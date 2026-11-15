<div align="center">

# Sahl TV · سهل
### Your TV. Your apps. Your home.

A calm, ad-free Android TV launcher with Arabic & English, Islamic essentials, a phone remote, and a layout you control.

[**Download SahlTV.apk**](https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/SahlTV.apk) · [All releases](https://github.com/AliSherif1309/SahlTV-Launcher/releases) · [Installation](#install-in-a-few-minutes) · [Report a problem](https://github.com/AliSherif1309/SahlTV-Launcher/issues)

[![Build APK](https://github.com/AliSherif1309/SahlTV-Launcher/actions/workflows/build.yml/badge.svg)](https://github.com/AliSherif1309/SahlTV-Launcher/actions/workflows/build.yml)
[![README review](https://github.com/AliSherif1309/SahlTV-Launcher/actions/workflows/readme-review.yml/badge.svg)](https://github.com/AliSherif1309/SahlTV-Launcher/actions/workflows/readme-review.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-2e7563)](LICENSE)

![Illustrated Sahl TV feature map: prayer dashboard, app rows, phone remote and floating timer](docs/images/home-overview.svg)

*Illustrated feature map, not an app screenshot. App names and times are examples; appearance depends on your settings and TV.*

</div>

## Why put Sahl on your TV?

- **Get straight to your apps.** Favorites, Recent and All Apps, with D-pad navigation and no advertising feed.
- **Make the layout yours.** Preview changes, choose row order, match tile sizes, arrange dashboard cards, and save named profiles.
- **Keep Islamic essentials close.** Prayer times and countdown, Hijri date, daily Ayah, adhkar, Ramadan and Sunnah fasting cards.
- **Use Arabic comfortably.** Right-to-left UI, packaged Thmanyah typography, 123 / ١٢٣ numerals, larger text and warmer night styling.
- **Control it from your phone.** Pair a browser on your home Wi-Fi to launch apps, control playback and type on the TV.
- **Keep a timer in view.** A remote-friendly countdown on Home, with an optional overlay above other apps.

**For Android TV / Mi TV running Android 8.0+ (API 26).** Firmware behavior varies; release validation uses Android TV emulators, and does not imply physical testing on every TV. Prayer calculations and selectable cities currently focus on **Qatar**.

## New and improved

| Feature | What you can do |
| --- | --- |
| **Home Layout 2.2** | Choose 3, 6 or 8 Recent apps and tight, normal or spacious tile spacing. |
| **Flexible dashboard** | Show/hide, reorder and resize upper cards; optionally arrange supported cards at full or half width. Prayer times stay full width. |
| **Visual layout editor** | See actual Favorites/Recent app names, choose the first row, and Apply or Cancel staged changes. Empty Favorites lets Recent appear first. |
| **Consistent app tiles** | Compact, Normal or Large proportions across every app row. Changing tiles keeps upper dashboard card sizes. |
| **Layout profiles** | Default, Minimal and Family presets, plus three named custom slots. |
| **Floating timer** | Set 1–180 minutes; start, pause, resume, reset, show/hide and choose a corner. Optional above-app mode requires overlay permission. |
| **Arabic prayer banners** | Prayer overlays now use Thmanyah and the saved numeral style. |

## A home screen you can arrange

![Layout guide comparing app tile sizes, row order and dashboard choices](docs/images/layout-guide.svg)

*Conceptual layout guide. Dimensions are the app's tile presets in dp; the diagram is not to scale.*

Open **Settings → Home screen & apps**. Choose app tile size, row visibility/order, combined or separate rows, Recent count and spacing. Adjust upper dashboard cards separately, preview your arrangement, then **Apply**. Save a profile to return to it later.

Hold **OK** or press **Menu** on an app to manage favorites, move it, hide it, open app info or uninstall it. A four-digit PIN can protect apps and optionally the TV settings button.

## Install in a few minutes

1. [Download SahlTV.apk](https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/SahlTV.apk) to your PC.
2. Enable developer options and USB/network debugging on the TV; find its IP address in network settings. Install [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) on your PC.
3. Connect and install, replacing the example IP with your TV's address:

   ```sh
   adb connect 192.168.1.50:5555
   adb install -r SahlTV.apk
   ```

4. Open **Sahl TV** from your TV's app list and try it with the remote.

Some TVs use wireless-debugging pairing and a different port; follow the address/pairing instructions shown by your TV.

<details>
<summary><b>Make Sahl the default Home screen · reversible ADB setup</b></summary>

Use your TV's default-home selector if it offers one. Otherwise, first confirm Sahl opens successfully, then list the Home apps:

```sh
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Identify the stock launcher package (not `com.aly.sahltv`), substitute its actual name below, and keep that name for recovery:

```sh
adb shell pm disable-user --user 0 STOCK_LAUNCHER_PACKAGE
```

Press **Home**. To restore the original launcher:

```sh
adb shell pm enable --user 0 STOCK_LAUNCHER_PACKAGE
```

Do not disable unrelated system packages. Keep ADB available until Home works.

</details>

## More than an app grid

| Everyday feature | How it helps |
| --- | --- |
| Prayer dashboard | Next prayer countdown, today's times and Sunrise; Qatar city selection, adjustments and optional silent prayer banners. |
| Islamic cards | Hijri date adjustment, Ayah of the day, adhkar, Ramadan/Iftar/Suhoor and upcoming Sunnah fasts. |
| Appearance & comfort | Islamic or Classic styling, mosque wallpapers, photo brightness, calm mode, text size and night style. |
| Phone remote | One-time pairing window, per-phone keys, playback controls and optional keyboard input. |
| Now playing | Playback and Open controls when media-session access is configured. |
| Optional app previews | Enable selected recommendation rows from apps that publish them; disabled by default. |
| Clock screensaver | A dim moving clock after idle time; wakes with a key and stays off during playback. |
| Backup & restore | Export preferences to `Download/SahlTV-backup.json` and keep a copy off the TV. |
| Diagnostics | See startup, app readiness, frame timing and memory measurements on your own TV. |
| Checked updates | Update from the TV; APK hash, package and signing checks protect installation. |

**Memory cleaning is optional.** Auto clean is off by default: keeping recently used apps in memory can make reopening them faster. The cleaner protects recent apps, current playback and Sahl apps.

### Pair your phone

On the TV, open **Settings → Tools & connection → Phone remote → Pair a phone**. On a phone connected to the same home Wi-Fi, open the address shown on the TV and enter its one-time six-digit code. Pairing lasts three minutes; five incorrect attempts close it. Manage or revoke devices under **Paired phones**.

The remote uses local HTTP and relies on trusted home Wi-Fi. Some actions need one-time ADB setup; see the [permissions guide](docs/REFERENCE.md#optional-permissions).

### Start a floating timer

Open **Settings → Tools & connection → Floating timer**. Set a duration, start it, and choose a screen corner. Home-only mode works without overlay permission. For **Above other apps**, follow the permission help, return Home, and explicitly enable the option.

The timer is a visual countdown, with no alarm sound or automatic TV shutdown. Some firmware and protected video can suppress overlays. See [timer behavior and limitations](docs/REFERENCE.md#floating-timer-android-tv).

## Sahl Timer · a standalone TV app

**Sahl Timer** works independently of the launcher. Open it from your TV app list, pick a quick duration (or **More** for 1–180 minutes), then press **Start**. The large countdown and **Pause / Resume**, **Reset** and **Start again** buttons work with the TV remote. Changing an active or paused countdown asks before replacing it.

To keep it visible while watching, select **Above apps**, grant the display-over-apps permission, return and turn the option on. Pick any screen corner. Permission is optional; the timer screen works without it. Return to Sahl Timer to control a floating countdown. It has no sound or automatic TV shutdown; some firmware and protected video block overlays. After a TV restart, resume the saved remaining time manually. The original launcher timer remains available and has separate state.

Open **Sahl apps** to find the rest of the family. Sahl TV offers checked installation and updates for Sahl Timer. The standalone app has Arabic and English labels and does not require Sahl TV to run.

## Sahl Connect · a standalone Android phone app

**Sahl Connect** joins the Sahl family as a separate phone app paired with the **main Sahl TV launcher**. It replaces the need for the separate Sahl TV Connect Preview launcher. Both devices need Android 8.0+ and the same private IPv4 Wi-Fi network.

1. Update the main **Sahl TV** launcher. Open **Settings → Tools & connection → Sahl Connect** (also available through **Sahl apps**).
2. Choose **Get phone app** and scan the public download QR with your phone camera. Install `SahlConnect.apk` on the **phone**, then choose **Options → Turn Sahl Connect on → Pair a phone** on the TV. Reopen Options once the encrypted connection is ready.
3. In Sahl Connect on the phone, choose **Scan TV QR** or enter the full pairing code. Keep the private pairing QR open on the TV. It expires after three minutes and works once.
4. Follow **Permissions & setup** for optional navigation and typing. File receiving requires separate TV consent; supported files up to 8 MB go to `Download/Sahl Connect` on Android 10+ TVs.

Separate Favorites keep their focus when Recent apps change.

Connect is off by default. It supports navigation, typing into an active TV field, media/volume controls, app launching, favourite add/remove and basic diagnostics. Some TV apps do not expose usable focus controls. Connect uses encrypted, certificate-pinned connections; the existing browser remote remains a separate option.

**Switching from the preview:** turn Connect off in the preview launcher, update the main Sahl TV, install the signed phone app, and pair again with the main launcher. If Android reports a phone-app signature conflict, uninstall the debug-signed phone preview first; this clears its pairing. Enable the main launcher's navigation service and keyboard if wanted. Once the main connection works, uninstall **Sahl TV Connect Preview** (`com.aly.sahltv.connectpreview`), keeping **Sahl TV** as your Home app. This change does not automatically uninstall anything from your devices.

Phone updates are downloaded using **Download latest Sahl Connect** in the phone app; the TV installer does not install phone APKs. See [Connect setup, migration and limitations](docs/SAHL_CONNECT.md).

## One Sahl family · five TV apps and Sahl Connect

Settings across the Sahl family use the Sahl theme: deep-green panels, soft-white text, rounded corners and gold remote-focus highlights. This includes settings choices and confirmation dialogs; Android permission and installer screens keep the system appearance.

Install only the apps you want. **Sahl apps** lists installed TV versions and opens companion apps; Sahl TV handles checked TV installs and updates. **Sahl Connect is installed on an Android phone**, with connection support built into the main Sahl TV launcher. Its family entry opens setup on Sahl TV and phone-install instructions in other TV apps.

<!-- BEGIN AUTO:APP_VERSIONS -->
| App | Source version | Minimum Android API | Download |
| --- | --- | --- | --- |
| Sahl TV | 1.26.1 | 26 | [SahlTV.apk](https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/SahlTV.apk) |
| Sahl Quran | 1.11.1 | 26 | [SahlQuran.apk](https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/SahlQuran.apk) |
| Sahl PDF | 2.6.1 | 26 | [SahlPDF.apk](https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/SahlPDF.apk) |
| Sahl Files | 1.6.1 | 26 | [SahlFiles.apk](https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/SahlFiles.apk) |
| Sahl Timer | 1.1.1 | 26 | [SahlTimer.apk](https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/SahlTimer.apk) |
| Sahl Connect (Android phone) | 1.0.1 | 26 | [SahlConnect.apk](https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/SahlConnect.apk) |
<!-- END AUTO:APP_VERSIONS -->

Versions above describe the source in this branch; download links point to the latest **published, validated** release and may lag an unmerged change.

- **Sahl Connect (phone):** Encrypted pairing with the main launcher, optional navigation and typing, media/volume, file sending and diagnostics.
- **Sahl Quran:** Mushaf and large-text views, Surah/Juz/page navigation, bookmarks, reading timer, daily goals, family readers and your own Mushaf PDF.
- **Sahl PDF:** Book covers, Continue reading, one/two-page view, RTL books, zoom, paper themes, bookmarks and PDF upload from your phone.
- **Sahl Files:** Internal/USB storage, copy/move/rename/delete, picture/text viewing and receiving files from your phone.

## Updates and reliability

On the TV, choose **Updates → Check for updates**. Auto-update preferences offer Off, Tell me, or Download & install; Android may still require you to confirm installation.

App and build-input changes on `main` produce releases; documentation-only changes do not. Versions use **major.minor.patch**: fixes increase patch, features increase minor, and breaking changes increase major. CI requires a higher version for each changed app (all six for shared code), and keeps SahlTV’s What’s new version in sync. Build numbers still increase for Android update compatibility; rebuilding the same source can retain its release version.

Every main-branch release builds all six apps, runs source and protocol checks, and validates candidate APKs on **1 GB and 2 GB Android TV emulators** plus an **Android phone emulator** before publication. The phone APK is signed with the family release key and checked for scanner dependencies and excluded font assets. Numbered releases contain APKs, SHA-256 fingerprints and performance reports. [View releases](https://github.com/AliSherif1309/SahlTV-Launcher/releases) · [Validation details and recovery](docs/REFERENCE.md#-updates-you-can-trust-and-recover-v120).

## Build and contribute

The project uses Kotlin, Android framework APIs, **JDK 17**, **Gradle 8.7** and Android SDK 34. Modules: `app`, `quran`, `pdf`, `files`, `timer`, and shared `common`.

See the [build workflow](.github/workflows/pr-compile.yml) for the complete generated-asset preparation and build sequence. A local build without signing secrets uses a debug key; without `FONT_KEY`, apps fall back to the system font. Thmanyah files are not redistributed as raw fonts.

Found a problem? [Open an issue](https://github.com/AliSherif1309/SahlTV-Launcher/issues) with your TV model, Android version, app version and steps to reproduce. Screenshots and **Settings → System & updates → Diagnostics** results help. Feature ideas and pull requests are welcome.

### Keeping this page current

[README review](.github/workflows/readme-review.yml) runs on every pull request and push to `main`, plus manual runs. It checks generated app versions, local documentation links/images, and whether changed implementation files have a documentation update or an explicit explanation. It writes a changed-file review summary and uploads a suggested patch when generated versions are stale.

For any feature or behavior change, update the relevant README section and [detailed reference](docs/REFERENCE.md), refresh illustrations when needed, and run:

```sh
python3 tools/readme_review.py --write
python3 tools/readme_review.py --check
python3 -m unittest discover -s tools -p 'test_readme_review.py'
```

For internal changes with no reader impact, put `README review: not needed — <specific reason>` in the PR description (or commit message for direct pushes). This records a human decision; the workflow does not claim to understand every feature or automatically invent feature descriptions. See [documentation maintenance](docs/DOCUMENTATION.md).

### Finding settings

Settings opens with categories for Home screen & apps, Appearance & comfort,
Language & clock, Prayer & Islamic, Tools & connection, Family & safety, and
System & updates. Search matches setting names, descriptions and current values
across all categories. Arabic search ignores vowel marks, tatweel and alef variants.
Back clears a search, returns from a category, then returns to the home screen.

Recently added settings display a small localized **New / جديد** badge until opened.
Opening a category alone does not dismiss its badges. Discovery state stays on the
TV across app updates. When adding a setting, place its action in `settingsSections()`
and register its discovery revision in `SettingsDiscovery.revision()`; raise the
revision only when an existing setting gains a substantial new capability.

## Credits and license

Code: [MIT](LICENSE). Quran text: [Tanzil](https://tanzil.net), unchanged Uthmani text, CC BY 3.0. Quran typography: Amiri Quran, SIL OFL. Thmanyah typography follows its separate font license; local builds use system fonts without the font secret. Wallpaper attribution is checked at build time and shown in About; see the [detailed credits and feature reference](docs/REFERENCE.md).

Contact: alysherif1309@gmail.com
