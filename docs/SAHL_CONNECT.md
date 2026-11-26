# Sahl Connect · Android phone companion

[← README](../README.md)

Sahl Connect is a standalone Android phone app in the Sahl family. The main Sahl TV launcher hosts its TV connection. No second launcher or separate TV Connect APK is needed. Both apps require Android 8.0+ (API 26). The phone screens currently use English and standard system fonts; the five TV apps retain their existing Arabic/English typography.

## Installation and pairing

1. Install or update the main `SahlTV.apk` from a validated family release. Leave Sahl TV as your default Home.
2. Open **Sahl TV → Settings → Tools & connection → Sahl Connect**, or **Sahl apps → Sahl Connect**. Choose **Get phone app** and scan the public download QR with your phone camera. Install `SahlConnect.apk` on your Android phone. Android may ask you to allow installation from the browser or file app used to open it. The public download QR grants no TV access.
3. On the TV select **Options → Turn Sahl Connect on**. Once the encrypted connection is ready, reopen **Options → Pair a phone (QR / full code)**. On the phone select **Scan TV QR**, or enter the full `sahl-connect://` code. Both devices must be on the same private IPv4 Wi-Fi network; guest Wi-Fi or client isolation can block the connection. The private pairing QR is different from the public download QR.
4. Keep the TV pairing screen open. The code expires in three minutes, works once, and closes after five incorrect attempts. Closing the screen cancels pairing. Scan only the code displayed by your own TV and keep it private.
5. Use the phone's **Permissions & setup** before remote controls. Refresh apps and diagnostics to check the connection and TV permission status.

## Optional permissions

- **Navigation:** enable **Sahl Connect navigation** for the main Sahl TV app in TV Accessibility settings. This allows a paired phone to move focus and press OK, Home and Back across apps. Android exposes the active window for focus navigation; Connect does not send its text to the phone. Some apps do not provide usable focus targets. Home can also return through the running launcher when Accessibility is disabled. Disable the service whenever you no longer want navigation access.
- **Typing:** in TV Keyboard settings enable and select the main launcher's **Sahl Keyboard**, then open a TV text field. Send text, Backspace or Enter from the phone. Select your usual keyboard manually when finished. Connect does not need an ADB or privileged settings grant. The phone distinguishes keyboard enablement, selection and an active text field.
- **Camera:** phone camera access is optional, used for QR scanning only. Manual full-code entry works without it.
- **Files:** separately select **Options → Allow file receiving (8 MB maximum)** on the TV. Android 10+ TVs save PDF, TXT, PNG/JPEG, MP3 and MP4 files, 1 byte–8 MB, in `Download/Sahl Connect`. APKs, archives and fonts are excluded. Files are not opened automatically. Transfers have a SHA-256 completion check and incomplete transfers are removed. Android 8–9 file receiving is unavailable.

Paired phones may open the TV Accessibility/Keyboard settings screens from the setup guide, but cannot enable permissions themselves. TV PIN protection is respected; use the physical TV remote for setup when protected. Favourite changes are also disabled when PIN protection is set. Pairing does not grant file-receiving consent.

## Retire the preview

The old test release contained `SahlTV-connect-preview.apk` (`com.sahl.tv.connectpreview`) and a debug-signed phone preview (`com.sahl.tv.connect`). The production phone app keeps the phone package ID and uses the family release signing key. Android will normally reject installing it over a differently signed debug preview.

1. Turn Connect off in the TV preview, which closes its listener on port 8766. Keep the main Sahl TV as Home.
2. Update the main Sahl TV launcher and install the signed phone app. If Android reports a signature conflict, uninstall the phone preview first, then install `SahlConnect.apk`. Uninstalling clears its saved TV pairing.
3. Pair again using the main launcher's QR. Preview pairings and TV identity are not migrated. Enable the main launcher's navigation service and select its Sahl Keyboard if wanted; the preview's permissions do not transfer.
4. Confirm the main launcher connects and controls work, then uninstall **Sahl TV Connect Preview** only. This removes its old credentials and service. Do not uninstall the main **Sahl TV** launcher. No device is automatically uninstalled or changed by this source update.

The preview build variant and preview publishing workflow are retired. Historical prereleases remain available as historical test artifacts; they are not the stable download channel.

## Security and lifecycle

Connect is off by default. Its dedicated TLS 1.2 endpoint uses TCP 8766, certificate pinning and a one-use 256-bit pairing secret. Each phone receives a separate random 256-bit credential stored encrypted outside Android backup. The TV stores credential hashes outside backup, permits individual/all revocation, limits pairing to ten phones and expires credentials after 90 days without use. TV identity uses Android Keystore. Reinstall/reset or a changed TV IP requires pairing again.

Use **Options → Paired phones / revoke access** to remove a phone or all phones. Turning Connect off closes connections and pairing and resets file-receiving consent; it preserves existing phone credentials until revoked. Disable navigation access and select your usual keyboard separately when no longer needed.

Connect runs in the launcher process. It cannot wake a sleeping or force-stopped TV, has no persistent foreground service and stores one TV on the phone. Media/volume behavior depends on the TV and audio output; fixed-volume outputs may require the TV/receiver remote. There is no cloud account, telemetry, screen-text transmission, transfer progress/resume/download, multi-TV profile, favourites reordering or Quran/PDF/Files sync.

The browser remote on HTTP port 8765 remains separate, with separate pairing and keys. Using Connect does not turn it on.

## Updates and validation

`SahlConnect.apk` is included in numbered family releases and the fixed `latest` download, SHA256SUMS and version.json. Production builds require the family release signing key. On the phone choose **Download latest Sahl Connect**, download the APK, and let Android confirm its installation. The existing app remains paired when updating with the same signing key. The TV updater intentionally installs only TV APKs; Connect's family entry provides phone setup/download instructions.

The release gate runs JVM protocol tests, Android lint, launcher integration tests on TV emulators, phone scanner/setup tests and a packaged runtime-class/font-exclusion check. Existing preview reports record user confirmation of QR fit, scanner operation and basic navigation on an Honor phone and Mi TV. Those results do not establish full hardware acceptance for this integration: third-party focus coverage, keyboard/media/volume edge cases, TLS provider compatibility, transfer interruption/revocation and idle lifecycle still need checking on target devices. A successful emulator run is not a physical TV performance claim.
