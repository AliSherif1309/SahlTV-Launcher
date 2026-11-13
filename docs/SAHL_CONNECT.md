# Sahl Connect 0.1 preview

Experimental Android phone APK, with an isolated TV preview APK. Build 35 and the stable `latest` release are unchanged. This is not a production release. The preview uses system fonts and never downloads, unlocks, packages or publishes font files.

## Install

Download both APKs and SHA256SUMS from a successful **Sahl Connect preview** Actions run or its `connect-preview-N` prerelease. Match COMMIT.txt to the run's commit. Verify SHA-256 before sideloading. The APKs are debug-signed test artifacts; do not treat their signing identity as a production update channel. A new CI run may use a different debug key: uninstall the previous preview if Android reports a signature conflict (this clears pairing).

1. On an Android 8+ phone, install `SahlConnect-preview.apk` and allow installation from the app used to open the APK if Android asks.
2. On an Android 8+ TV, install `SahlTV-connect-preview.apk`. Its package is `com.aly.sahltv.connectpreview`, separate from stable `com.aly.sahltv`. Keep stable SahlTV as your default Home while testing. Open the preview from Apps, or use:

   ```text
   adb install SahlTV-connect-preview.apk
   adb shell am start -n com.aly.sahltv.connectpreview/com.aly.sahltv.MainActivity
   ```

3. In the preview TV launcher, open Settings → Sahl Connect (preview) → Options → Turn on. Reopen Options once the encrypted connection says ready. Select Pair a phone. On the phone, scan that QR or enter its full code. Keep both devices on the same private IPv4 Wi-Fi network. Guest Wi-Fi/client isolation may block the connection.
4. For D-pad, OK and Back across apps, explicitly enable **Sahl Connect navigation** for the preview in TV Accessibility settings. Home also works without this service while the launcher process is available. Some TV apps expose no usable accessibility focus; these controls return an unavailable result. No claim of universal key injection is made.
5. To type, enable and select the preview's **Sahl Keyboard** in TV keyboard settings, then open a text field. Select your normal keyboard again when finished. No privileged ADB grant is needed for this path.
6. To transfer a file, separately allow file receiving in the TV Connect options, then choose a document on the phone. Android 10+ TVs save files to `Download/Sahl Connect`. V1 accepts PDF, TXT, PNG/JPEG, MP3 and MP4, 1 byte–8 MB, with a SHA-256 completion check. Fonts, APKs, archives and other extensions are excluded. Partial transfers are deleted; files are not opened automatically.
7. To revoke access: TV Connect → Paired phones → choose a phone or revoke all. Turning Connect off closes connections and pairing and resets file-receiving consent. Disable the Accessibility service and switch keyboards back if no longer needed. Uninstall the preview to remove it; stable SahlTV is unaffected.

## Implemented scope

- Separate phone application using the existing shared Sahl colours and UI helpers, with standard system fonts.
- Reusable `connect-protocol` module and shared TLS/credential storage for future Sahl family integration.
- Dedicated TLS 1.2 endpoint on TCP 8766; no cleartext fallback. QR/full-code pairing pins the TV's certificate before sending secrets. The short browser-remote code is intentionally not accepted by this protocol.
- TV-only pairing window: 3 minutes, one use, 5 failed attempts close it; 256-bit random secret. The displayed code is private and must come from the user's own TV.
- Per-phone 256-bit bearer keys over pinned TLS, stored encrypted outside phone backup. TV stores only key hashes, also outside backup, with individual/all revocation, 10-phone limit and 90-day inactivity expiry. TV identity uses Android Keystore; reinstall/reset requires pairing again.
- Single-TV remote: D-pad/OK/Home/Back, media keys and media-stream volume; active-input keyboard text, Backspace and Enter; installed-app launching; favourite add/remove (disabled when TV PIN protection is set).
- Opt-in bounded file receiving and simple Android/version/RAM/permission diagnostics. No cloud, telemetry, account system or screen-text transmission.

## Validation and limits

The workflow runs JVM pairing/framing tests, compiles both preview APKs, and runs Android lint. Tests cover one-use/expiry/lockout/cancellation, concurrent pairing, malformed/truncated/oversized frames, and LAN/code validation. A successful build is not a hardware performance or navigation result.

Not yet verified on physical TV/phone hardware: TLS provider compatibility, QR camera flow, accessibility focus in third-party apps, keyboard behavior, media/volume routing, transfer interruption/revocation and idle lifecycle. Connect is hosted in the launcher process rather than a persistent foreground service; it cannot wake a suspended/force-stopped TV. DHCP address changes require pairing again. Only one TV is stored on the phone. Android 8–9 file receiving is not implemented. Favourites reordering, transfer progress/cancellation, Android TV remote-protocol integration, Arabic translations for the new screens and accessible app-specific control alternatives remain unfinished.

There are no SahlPDF/Files/Quran sync endpoints yet. Future work: hardware acceptance testing first, then multi-TV profiles, foreground-service lifecycle, production signing, progress/resume, Sahl Files inbox handoff, PDF library sending and opt-in Quran reading-position sync. Do not merge this preview or promote it into the stable update channel until hardware acceptance passes.

The original browser remote on port 8765 is a separate existing feature using HTTP. Pairing with Sahl Connect does not enable it or reuse its keys. Leave the browser remote off when testing the encrypted phone app.
