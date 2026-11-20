# Stock Android screens in VoidOS 1.19.0

## Replaced by VoidOS

- Home, app drawer and widgets
- Navigation bar and status bar (hidden; the system zip removes the bar at boot)
- App switcher (bottom-edge swipe)
- Control Centre / quick settings
- Power menu (hold power), plus Restarting / Powering off cover screens
- Volume panel (VoidOS sliders; the keys never reach Android)
- Lock screen PIN pad (VoidOS pad on top of Android's, after the first unlock)
- Low battery warning (20%, 10%, 5%)
- Screenshot and screen recording (done through root, so no Android consent dialog)
- VoidOS's own permission prompts (granted up front by the system zip)
- Guest Mode, Kids Space and user switching (blocked)

## Can't be replaced, and why

- **First unlock after a restart.** The tablet's storage is encrypted and only Android's own lock screen can unlock it. VoidOS can't run until that first unlock, so Android's PIN pad shows once per boot.
- **Boot, recovery and Magisk safe mode.** These run before Android, and VoidOS with it, has started. The boot and shutdown animations are already VoidOS's own.
- **Android's grey shutdown box.** Android draws it itself while it shuts down. VoidOS covers it with its own screen, but it can flash for a moment.
- **Android's power dialog.** From 1.20.0 (with system module 1.3) it no longer opens when power is held: the hold goes straight to VoidOS's menu. It comes back only if VoidOS or its key service is missing, so there's always a way to power off.
- **The keyboard.** Typing goes through the installed keyboard app (Gboard or the tablet's own). A home app can't draw the keyboard for other apps.
- **Other apps' permission prompts** ("Allow Spotify to…"). Android shows these for each app. VoidOS only pre-grants its own.
- **App not responding and crash dialogs.** Android raises these for other apps. Hiding them would leave a frozen app on screen with no way to close it.
- **Share sheet and "Open with".** Other apps open Android's chooser directly. VoidOS isn't asked.
- **Bluetooth pairing codes, USB debugging and "Use USB for" prompts.** These are security checks Android shows itself, so a home app can't answer them.
- **Android Settings.** VoidOS's System page covers the everyday settings. "All settings" and the deeper links on that page still open Android's Settings app, which a home app can't redraw.
