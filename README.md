# Sahl TV (سهل): a fast, ad-free home screen for Android TV

A replacement home screen for Android TV / Mi TV. It opens instantly, shows only your apps, has no ads, and is easy to use with the remote. It's in **English and Arabic** (العربية, right-to-left), switchable in the app.

**⬇ Download:** [SahlTV.apk](https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/SahlTV.apk)

## What you get
- **Clock and dates:** the Gregorian date plus the Hijri date (Umm al-Qura).
- **Next prayer** in the top bar, with a countdown and "Jumu'ah" on Fridays. It's calculated on the TV with the Qatar method (Fajr 18°, Asr Shafi'i, Isha 90 minutes after Maghrib), and you can pick your city in Qatar.
- **Favorites row:** your main apps as big tiles, in the order you choose.
- **Recent row:** the apps you opened last.
- **All apps:** sorted by most used (or A–Z), using the TV app banners where apps provide them.
- **Hold OK (or press Menu) on any app:** Add to / Remove from Favorites, Move left / right, Hide, App info, Uninstall.
- **🧹 Free memory:** closes apps running in the background and shows how much memory was freed. **Auto clean** does this by itself when you return Home and memory is low.
- **🔒 PIN lock:** lock any app, and optionally the TV settings button, with a 4-digit PIN entered on a remote-friendly number pad.
- **⚙ TV settings** and **☰ Launcher** options: sort order, 12/24-hour clock, show hidden apps.
- **App previews (optional, off by default):** rows that apps publish for the home screen (e.g. recommended videos from SmartTube, or "Continue watching"). Each row can be switched on or off in ☰ Launcher → App previews. This needs one permission: `adb shell pm grant com.aly.sahltv android.permission.READ_TV_LISTINGS`. Pictures are downloaded only while previews are on.
- **Now playing bar:** shows what's playing (e.g. SmartTube in picture-in-picture or background audio) with ⏮ ⏯ ⏭ and Open buttons. One-time setup: `adb shell cmd notification allow_listener com.aly.sahltv/com.aly.sahltv.MediaListener` (Sahl TV reads no notifications).
- **Clock screensaver:** after 3, 5 or 10 idle minutes on the home screen, a dim clock with the dates and next prayer that slowly moves around the screen (safe for OLED). It doesn't start while something is playing; any key wakes it.
- **Backup & restore:** saves all settings (favourites, order, hidden and locked apps, previews, language…) to `Download/SahlTV-backup.json` on the TV. Keep a copy on your PC with `adb pull /sdcard/Download/SahlTV-backup.json`.
- **No tracking, no background services.** Apps load in the background and icons are kept in memory, so the Home button is instant.

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

## Build
Kotlin, one screen, no libraries. Every push to `main` builds a signed APK with GitHub Actions and publishes it as the **latest** release.
Release signing uses the repository secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD` and `SIGNING_KEY_ALIAS`.

## License
[MIT](LICENSE). Contact: alysherif1309@gmail.com

## 📱 Phone remote (v1.4)
Turn on in ☰ Launcher → Phone remote. Open the shown address (http://TV-IP:8765) on a phone on the same Wi-Fi and enter the 6-digit code.
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
