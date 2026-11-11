# Sahl TV (سهل): a fast, ad-free home screen for Android TV

A replacement home screen for Android TV / Mi TV. It opens instantly, shows only your apps, has no ads, uses no internet, and is easy to use with the remote.

**⬇ Download:** [SahlTV.apk](https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/SahlTV.apk)

## What you get
- **Clock and dates:** the Gregorian date plus the Hijri date (Umm al-Qura).
- **Favorites row:** your main apps as big tiles, in the order you choose.
- **Recent row:** the apps you opened last.
- **All apps:** sorted by most used (or A–Z), using the TV app banners where apps provide them.
- **Hold OK (or press Menu) on any app:** Add to / Remove from Favorites, Move left / right, Hide, App info, Uninstall.
- **🧹 Free memory:** closes apps running in the background and shows how much memory was freed.
- **⚙ TV settings** and **☰ Launcher** options: sort order, 12/24-hour clock, show hidden apps.
- **No internet permission, no tracking, no background services.** Apps load in the background and icons are kept in memory, so the Home button is instant.

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
