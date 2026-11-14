# Sahl TV · detailed feature reference

[← Back to the visual README](../README.md)

This reference preserves setup details and feature history. Later version sections supersede earlier behavior.

A replacement home screen for Android TV / Mi TV. It opens instantly, shows only your apps, has no ads, and is easy to use with the remote. It's in **English and Arabic** (العربية, right-to-left), switchable in the app.

**⬇ Download:** [SahlTV.apk](https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/SahlTV.apk)

## What you get
- **Clock and dates:** the Gregorian date plus the Hijri date (Umm al-Qura).
- **Next prayer** in the top bar, with a countdown and "Jumu'ah" on Fridays. It's calculated on the TV with the Qatar method (Fajr 18°, Asr Shafi'i, Isha 90 minutes after Maghrib), and you can pick your city in Qatar.
- **Favorites row:** your main apps as big tiles, in the order you choose.
- **Recent row:** the apps you opened last.
- **All apps:** sorted by most used (or A–Z), using the TV app banners where apps provide them.
- **Hold OK (or press Menu) on any app:** Add to / Remove from Favorites, Move left / right, Hide, App info, Uninstall.
- **🧹 Free memory:** closes apps running in the background and shows how much memory was freed. **Auto clean** is optional and off by default; see the Speed section for current safeguards.
- **🔒 PIN lock:** lock any app, and optionally the TV settings button, with a 4-digit PIN entered on a remote-friendly number pad.
- **⚙ TV settings** and **☰ Launcher** options: sort order, 12/24-hour clock, show hidden apps.
- **App previews (optional, off by default):** rows that apps publish for the home screen (e.g. recommended videos from SmartTube, or "Continue watching"). Each row can be switched on or off in ☰ Launcher → App previews. This needs one permission: `adb shell pm grant com.aly.sahltv android.permission.READ_TV_LISTINGS`. Pictures are downloaded only while previews are on.
- **Now playing bar:** shows what's playing (e.g. SmartTube in picture-in-picture or background audio) with ⏮ ⏯ ⏭ and Open buttons. One-time setup: `adb shell cmd notification allow_listener com.aly.sahltv/com.aly.sahltv.MediaListener` (Sahl TV reads no notifications).
- **Clock screensaver:** after 3, 5 or 10 idle minutes on the home screen, a dim clock with the dates and next prayer that slowly moves around the screen (safe for OLED). It doesn't start while something is playing; any key wakes it.
- **Backup & restore:** saves all settings (favourites, order, hidden and locked apps, previews, language…) to `Download/SahlTV-backup.json` on the TV. Keep a copy on your PC with `adb pull /sdcard/Download/SahlTV-backup.json`.
- **No tracking.** Optional features use Android services (media access and timer overlays). Apps load in the background and icons are kept in memory, so the Home button is instant.

## Customize the home layout
Open **Sahl TV → Settings → Home layout** to access the visual layout preview, upper dashboard card controls, and layout profiles. The visual editor lets you change app tile size, combined or separate rows, Favorites/Recent visibility, and which row comes first before applying. Its preview displays your actual Favorites and Recent app names. If Favorites is empty or hidden, Recent becomes the first available app row.

Favorites, Recent, combined and All Apps share identical tile proportions at Compact (130 × 73), Normal (160 × 90), or Large (200 × 112). **The upper dashboard cards are not resized by app tile settings.** The upper-card editor supports show/hide, height presets and ordering. Its optional **Flexible cards** mode also allows cross-group reordering and full-width/half-width card arrangements, with a live layout-plan preview. The original dashboard stays the default; prayer times remain full width for legibility. Upper cards are not scaled down when you adjust app tiles.

Profiles (Default, Minimal, Family) can save and restore layouts; three additional custom slots let you name and save your own arrangements. Use remote arrows and OK to navigate, then choose **Apply** to commit changes.

## Install (from a Windows PC with ADB)
1. On the TV, turn on **Developer options → USB debugging / Network debugging**, and note the TV's IP address (Settings → Network).
2. On the PC, download `SahlTV.apk`, then run:
   ```
   adb connect 192.168.1.50:5555        (use your TV's IP)
   adb install -r SahlTV.apk
   ```
3. Open **Sahl TV** from the TV's app list to try it.

## Make it the home screen
Android TV doesn't show a "choose home app" prompt, so turn the stock launcher off with ADB:
```
# 1) list the home-screen apps; the one that isn't com.aly.sahltv is the stock launcher
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME

# 2) disable the stock launcher (replace <package> with the name from step 1)
adb shell pm disable-user --user 0 <package>
```
Press **Home** on the remote: Sahl TV opens.

**To undo** at any time: `adb shell pm enable <package>`

## Optional: make the whole TV feel faster
```
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
```
To undo, set the three values back to `1`.

Home Layout 2.2 adds optional **Recent count** (3, 6 or 8) and **tile spacing** (tight, normal or spacious) controls inside the staged Visual layout editor. Defaults remain six Recent apps and normal 6 dp tile margins; card dimensions and upper dashboard proportions stay unchanged. These settings are included in saved layout profiles and reset with Home layout.

## Build
Kotlin and Android framework APIs, with five Gradle modules (`app`, `quran`, `pdf`, `files`, `common`). Every push to `main` builds release APKs with GitHub Actions and publishes a numbered release plus the `latest` update pointer. Publication waits for source regression checks and candidate validation on Android TV API 29 emulators with 2 GB and 1 GB RAM. Both test the exact candidate APK artifact, not the mutable latest release. Pull requests build all four debug apps and run the same validation.
Release signing uses the repository secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD` and `SIGNING_KEY_ALIAS`.

## License
[MIT](../LICENSE). Contact: alysherif1309@gmail.com

## 📱 Phone remote (v1.4)
Open Settings → Phone remote → Pair a phone to open a temporary pairing window. Open the shown address (http://TV-IP:8765) on a phone on the same Wi-Fi and enter the 6-digit code.
Launch apps, play/pause/next, volume, Home, and type text into any search box.
For typing, run once:
```
adb shell ime enable com.aly.sahltv/.RemoteKeyboard
adb shell pm grant com.aly.sahltv android.permission.WRITE_SECURE_SETTINGS
```

## 📖 Sahl Quran (separate app: SahlQuran.apk)
Quran reader for the TV: continues where you stopped, jump by Surah / Juz' / page, bookmarks, text size, dark / light / sepia.
Remote: ◀ ▶ turn pages, ▲ ▼ scroll, OK opens the menu.

Quran text: Tanzil Project (tanzil.net), Uthmani, CC BY 3.0, used unchanged. Font: Amiri Quran, SIL Open Font License.

## ☪ Islamic theme (v1.5)
- Night-sky background with a crescent, mosque silhouette and a faint gold star pattern (drawn once, no images, no slowdown). Switch to Classic in ☰ Launcher → Theme.
- Next prayer with a live countdown, and all five prayers with the next one highlighted. Press OK for today's full times.
- Hijri date under the clock, with a ±2 day adjustment for local moon sighting.
- Ramadan card: days until Ramadan; during Ramadan, the day number and a live Iftar / Suhoor countdown; Eid and Arafah greetings. Press OK for upcoming Islamic dates.
- Sunnah fast card: next Monday/Thursday, White days, Arafah, Ashura, Dhu al-Hijjah and Shawwal fasts; glows the day before. Press OK for the next 30 days.

## v1.6: calmer look, prayer-time banner, calm mode
- Calmer Islamic theme: deeper colours, very faint pattern, small dark mosque on the horizon, matching gold line icons.
- 🔔 Prayer-time banner (no sound): a quiet card in the corner at each prayer time, even over SmartTube; optional heads-up 10 minutes before. One-time setup:
  `adb shell appops set com.aly.sahltv SYSTEM_ALERT_WINDOW allow`
- 👶 Calm mode (on by default): countdown changes once a minute instead of every second, softer focus, home screen dims from an hour after Isha until Fajr.

## ⬆ Updates from the TV (v1.7)
After this version, no more ADB for updates: ☰ Launcher → Check for updates (Sahl Quran: OK → Check for updates).
The launcher also checks by itself twice a day and shows an "Update ready" button.
One-time setup (only if the TV has no "Install unknown apps" screen):
```
adb shell appops set com.aly.sahltv REQUEST_INSTALL_PACKAGES allow
adb shell appops set com.aly.sahlquran REQUEST_INSTALL_PACKAGES allow
```
Updates come from this repository's "latest" release. Android only installs an update signed with the same key, so a changed APK can never replace the app.

## v1.8
- Background: real photo of Masjid al-Haram and the Kaaba, darkened for calm, readable text.
  Photo: “The Kaaba during Hajj” by Adli Wahid, CC BY-SA 4.0, via Wikimedia Commons (cropped and darkened at build time).
- New Settings screen: grouped sections, a short explanation under every option, current value on the right.
- ⬆ Updates button always on the home screen (turns gold when an update is ready).
- Fixed card borders being cut at the edges.

## v1.9
- Backgrounds: the Kaaba (day), the Kaaba (night), the Prophet's Mosque, Al-Aqsa (Dome of the Rock), or Automatic: Kaaba by day, at night from Maghrib to Fajr.
  Photos come from Wikimedia Commons; the build checks each licence (only CC BY / CC BY-SA / public domain are used) and lists the credits in About.
- Photo brightness (5 levels), Auto update (Off / Tell me / Download & install), round gold icon buttons, larger clock.
- Phone remote: a new code is made after 10 wrong tries. The background photo is prepared in the background (no stall).

## v1.10
- Prayer times follow the Qatar Calendar House (Fajr −1, Dhuhr +1, Asr +1, Maghrib +2 min, Isha = Maghrib + 90), checked against its Doha timetable; Sunrise (Shorouq) added; fine-tune in Settings.
- Ayah of the day (verses copied unchanged from the verified Tanzil text at build time); press OK to open it in Sahl Quran.
- Weather (Open-Meteo, fetched in the background) and the next Qatar occasion next to the clock.
- "What's new" card for two days after each update.

## Sahl Quran 1.3
- Mushaf view: an open Mushaf on the TV — right page odd, left page even, the same 604 pages and the same verses per page as the Madinah Mushaf; cream paper, gold-and-green frame, ornamental surah headers, basmala, round verse markers, surah and juz' at the top, page number at the bottom. "Large text" view is still there.
- Reading timer: start from the menu; a small chip shows the time and pages; it pauses when you leave; at the end you see the session and today's total.
- Fixed: Mushaf page numbers and "Go to Mushaf page" were missing (the page list's end marker made it 605 entries).

## Sahl Quran 1.4
- My Mushaf (PDF): send your own Mushaf PDF (e.g. the Madinah Mushaf or a Tajweed Mushaf) from your phone to the TV (menu → My Mushaf → open the address on the phone, enter the code, pick the file). Line it up once ("this is Al-Fatihah"), then it shows as an open book, page for page. The file stays on your TV only; it is not part of this app.
- Timer: pause / resume (also the ⏯ key), stop with a summary; time is saved as you read.
- Daily goal (minutes), auto-start timer, readers (4 family members with their own place, bookmarks and totals).
- One large page (▲ ▼), night paper (automatic 7 pm – 5 am), go to hizb, ۞ quarter marker, sajdah reminder, pure page.

## 📚 Sahl PDF 2.0 and 📁 Sahl Files (v1.12)
Two small companion apps, built here with no external libraries and offered by ⬆ Updates in Sahl TV.

**Sahl PDF (SahlPDF.apk)**: a simple book reader in the Sahl style. It replaces the earlier MJ PDF based edition and installs over it, keeping the books in `Download/Sahl PDF`.
- Library: *Continue reading* first, then all books with big covers (sort by name or recent).
- Reader: one or two pages, right-to-left books, zoom and move with the arrows, white / warm / dark paper, bookmarks, go to page. It remembers where you stopped.
- *Send from phone*: open the address shown on the TV, type the 6-digit code, pick PDFs. They are sent once and stay on the TV.

**Sahl Files (SahlFiles.apk)**: a simple file manager.
- Places on the left: Internal storage, Download, Movies, Pictures, Music, Documents, the books folder, and USB drives.
- OK opens a file: PDFs open in Sahl PDF, pictures and text in the built-in viewer (with a slideshow), videos and music in your player, APKs in the installer.
- Hold OK or press Menu on a file for Copy, Move, Rename, Delete and Details.
- *Receive from phone*: the same address-and-code page, saving into the folder you are in.

Both apps ask once for access to files (Android 10 shows a normal Allow window).

## ✍ Thmanyah font (v1.13)
All four apps use the Thmanyah family (ثمانية), following its own guide: **Serif Display** for titles and headings, **Serif Text** for text you read (messages, notes, book titles, text files), and **Sans** for the screen, buttons and numbers (the clock uses Sans Bold; the screensaver clock uses Sans Light). Quran pages keep the Amiri Quran Mushaf font.

**Headings follow Thmanyah's guide**: each short heading gets one touch on its last word only: a swash ending (the font's alternate letter) when the word ends in ب ت ث س ش ص ض ف ق ك م ن ي ى ئ, otherwise one slanted kashida between the last two letters when they join. Never in long text.

**Night style** (on by default): from Isha until Fajr, all four apps turn warmer and softer (less blue light) and switch to the reading font, Serif Text. Sahl TV keeps the prayer times; the other apps ask it.

**Numbers**: 123 or ١٢٣ for the clock, prayer times and countdown.

**Text size** (Sahl TV → Settings → Look & comfort): Normal, Large or Extra large, for all four apps. The other apps read it from Sahl TV.
The font's licence allows using it inside apps but not sharing the font files. So this repository keeps only an encrypted copy (`fonts-locked/`). The cloud build unlocks it with the `FONT_KEY` secret and packs it scrambled into each app (`tools/pack_fonts.py`, `Fonts.kt`). Without the secret, the apps build with the TV's normal font.
Font © thmanyah Publishing and Distribution, used under thmanyah's Font License.

## 🔐 Phone remote security (v1.20)
- **Pairing only on request:** on the TV, Settings → Phone remote → **Pair a phone** opens pairing for 3 minutes with a new one-time code. 5 wrong codes close it. Outside that window no code is accepted at all.
- **One key per phone:** each phone gets its own random 256-bit key. The TV stores only its SHA-256 fingerprint, with the phone's name and when it was last used. Phones unused for 90 days are forgotten. **Paired phones** lets you remove any phone, or all of them.
- **Home network only:** connections from outside private addresses are refused. Requests must use the TV's IP address, not a domain name, which blocks websites from reaching the TV through the phone's browser (DNS rebinding). Pages are sent with anti-framing and no-referrer headers.
- The TV shows a message whenever a phone is paired.
- The same network checks protect the "send from phone" pages of Sahl Quran, Sahl PDF and Sahl Files.
- Limitation: it is plain HTTP inside your Wi-Fi (a browser cannot use HTTPS with a TV that has no certificate), so it relies on your Wi-Fi password, like other home-network TV remotes.

## ⬆ Updates you can trust and recover (v1.20)
- **Numbered releases:** every build is published as `build-N` with its APKs, `SHA256SUMS`, `version.json` and notes taken from the app's What's new. These releases are never changed afterwards.
- **`latest` is only a pointer:** it is no longer deleted and recreated. Its files are replaced in place, and `version.json` is uploaded last. It tells the TV which build to use, and gives each APK's address in the numbered release with its SHA-256 fingerprint.
- **The TV checks before installing** (`UpdateCheck`): the fingerprint must match, the file must be the expected app, and it must be signed with the Sahl key, even for an app being installed for the first time. Otherwise nothing is installed.
- **Recovery:** if a version misbehaves, go to GitHub → Actions → *Build APK* → **Run workflow**, and type an older tag in *ref* (e.g. `build-128`). That code is published again with a newer number, so TVs update "forward" to it. Android does not allow installing an older version number over a newer one without uninstalling. Recovery works for builds from v1.20 onwards.
- Optional extra protection: in the repository settings, turn on **immutable releases**, so release files cannot be changed once published.

## 📊 Measured performance (v1.20)
- **On the TV:** Settings → **Diagnostics** shows:
  - cold start and return-to-Home times, measured until the first frame;
  - smoothness (frames on time, slow, frozen, worst);
  - memory (Sahl TV's own use, free memory, low-RAM flag);
  - for each memory clean: MB freed, and how much is **still free after 1 and 5 minutes**, with a verdict on whether cleaning really helps on this TV.
- **Before publishing** (`.github/workflows/validate.yml`): two Android TV emulators (2 GB and 1 GB RAM) measure:
  - cold and warm start (5 runs each);
  - memory use;
  - 60 remote key presses (frames, jank, p50/p90/p99, crashes);
  - startup, PSS memory, D-pad navigation, crash and rendered-frame checks remain mandatory. The destructive system-wide `am kill-all` baseline is optional (`PERF_KILL_ALL_BASELINE=1` for a separate diagnostic run) because on emulators it can terminate ADB/the emulator itself; skipping it is explicitly shown in the report and is not a measurement of the selective broom cleaner.

  Reports are attached when publishing the numbered release. Missing measurements, launcher crashes/death, zero rendered frames, median cold start above 10 seconds, apps-ready time above 15 seconds, or PSS above 256 MB block publication. These are broad emulator budgets, not physical TV guarantees. Jank is reported without a threshold.

  Framework instrumentation tests use real D-pad events to check editor focus after redraw, navigation, staged Cancel and Apply behavior. Security regressions exercise LAN peer/Host rejection, token comparison, hashes and update tampering/package rejection. UI/security instrumentation uses a debug target; performance runs against the candidate release APKs on main. No physical TV testing is implied. Historical comparisons remain available manually through perf.yml. Recovery refs without the required validation sources fail closed.

## 🧩 Code structure (v1.20)
- `:common`: the Sahl family, shared by all four apps:
  - Thmanyah fonts and night style;
  - colours and dialogs;
  - the phone upload page;
  - storage helpers;
  - network safety (`LanGuard`);
  - update checks (`UpdateCheck`).
- `app/.../MainActivity.kt` keeps the home screen's state, lifecycle and callbacks. Each part has its own file: `AppCatalog`, `HomeRows`, `PreviewRows`, `TopBar`, `Wallpaper`, `PrayerPanel`, `Greetings`, `HomeCards`, `Comfort`, `SettingsScreen`, `Menus`, `Pin`, `PhoneRemote`, `NowPlaying`, `Screensaver`, `Backup`, `Memory`, `Diagnostics`.

## ⚡ Speed (v1.21)
- **Apps at once:** the app list, with ready-drawn banners and icons, is saved on the TV (`AppCache`). At start-up the rows come from that copy immediately. Then the real list is read in the background, and only apps installed or updated since are redrawn.
- **First frame first:** the screen appears with its time and date. Prayer times, Islamic cards, previews, media, alarms and update checks fill in right after the first frame.
- **Hijri dates remembered:** the Islamic calendar is slow to set up, and the fasting search looks up to 40 days ahead, so dates already computed are kept.
- **Fonts on demand:** the reading font (Serif Text) and Light load the first time they are needed.
- **Smarter cleaning:** your 3 most recent apps, anything used in the last 30 minutes, the app playing in the background, and the Sahl apps are never closed. **Auto clean is off by default**, because the measurements showed closed apps open 5–7× slower. The broom explains this the first time.
- **Measured:** the emulator test now also reports *apps on screen after a cold start*. Settings → Diagnostics shows it on your TV.

## ✦ One family
"Sahl apps" (Sahl TV Settings, and the menus of Quran, PDF and Files) lists the four apps with their versions. It opens an installed one, and installs a missing one through Sahl TV, the family's single, checked update centre.


### Floating timer (Android TV)

Settings → Look & comfort → Floating timer. Use D-pad and OK to choose a duration
(1–180 minutes), start, pause/resume, reset, show/hide, or choose one of four
physical screen corners. Back closes menus. Reset restores the selected duration
and leaves it paused. A completed countdown reads 00:00 on Home; reset to run again.
The timer is hidden by default and adds no home card or layout preference.

The default is a Home-only visual timer. Elapsed realtime keeps it accurate across
clock changes, sleep and Activity/process recreation on the same boot. Reboot
pauses at the last saved remaining time. No sound, exact alarm, screen wake, app
termination, or TV shutdown is promised.

**Above other apps** is optional and off by default. Open the permission help in
the timer menu, grant Android's *Display over other apps* permission, return to
Home, then enable the option. Granting permission alone never enables it. Some TV
firmware has no permission screen or refuses overlays; Home-only mode still works.
A non-exported foreground service owns a `TYPE_APPLICATION_OVERLAY` window with
`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`, so videos and remote navigation continue
normally. Control it through Home → Settings → Floating timer. Hide or disable the
option to remove the overlay/service; completion also removes it. While paused
and visible, the overlay stays until hidden. Position and visibility are saved
separately from all existing dashboard, cards and layout profiles.

The service starts only from visible launcher interactions/onResume, with a
required low-priority notification. No boot/background receiver restarts it.
Permission revocation or window/service errors remove the overlay; return Home
for the fallback. Android/OEM resource limits and apps hiding overlays (including
protected playback) can suppress it: this is not a guaranteed alarm. Android 14
uses the declared `specialUse` foreground-service type and permission; Play Store
distribution may require special-use review. Android 15+ background-start
exemptions are not relied upon. See Android's
[overlay permission](https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW)
and [foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

Tests: `FloatingTimerTest` runs in the existing PR Android TV instrumentation
matrix (1 GB and 2 GB), covering deadlines, pause/resume/reset, completion,
bounds, persistence/reboot and non-focusable rendering. Physical TV validation is
still needed for vendor permission screens, D-pad traversal, protected video,
and permission revocation on each supported firmware.


## Optional permissions

Enable only features you want, using ADB from your PC.

| Feature | One-time setup |
| --- | --- |
| App previews | `adb shell pm grant com.aly.sahltv android.permission.READ_TV_LISTINGS` |
| Now playing | `adb shell cmd notification allow_listener com.aly.sahltv/com.aly.sahltv.MediaListener` |
| Phone keyboard | `adb shell ime enable com.aly.sahltv/.RemoteKeyboard` and `adb shell pm grant com.aly.sahltv android.permission.WRITE_SECURE_SETTINGS` |
| Prayer banners / timer overlay permission | `adb shell appops set com.aly.sahltv SYSTEM_ALERT_WINDOW allow`; explicitly enable Above other apps in the timer menu too |
| TV updates if no permission screen exists | `adb shell appops set com.aly.sahltv REQUEST_INSTALL_PACKAGES allow` |

Permission screens vary by firmware. None of these are needed to try the basic launcher.

## Arabic prayer overlay update

Prayer overlays now use the packaged Thmanyah family and saved 123 / ١٢٣ numeral preference, including the Arabic UI and live countdown.
