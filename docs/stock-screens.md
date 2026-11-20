# Stock Android surfaces in VoidOS 2.0.1

Every Android surface you can meet from VoidOS, and what 2.0.1 does with it. "System zip" means
`root/voidos-system-magisk.zip` (version 1.5). Nothing here has been checked on the tablet yet.

## Replaced by VoidOS

- **Home, app drawer, widgets, recents.** VoidOS is the home app. Its app switcher and edge drawers replace Android's recents, which can't be reached: there is no navigation bar and no recents key.
- **Status bar.** Switched off (device owner `setStatusBarDisabled`, immersive policy, and the no-bar overlay in the system zip).
- **Navigation bar.** Removed at boot by the system zip's framework overlay (`config_showNavigationBar=false`).
- **Notification shade and quick settings.** The shade can't be pulled down while the status bar is disabled. VoidOS's Control Centre has the toggles and its own notification list.
- **Heads-up banners.** Switched off by the system zip (`heads_up_notifications_enabled 0`). VoidOS shows new notifications itself through its notification listener.
- **Keyboard.** The VoidOS keyboard (UK QWERTY, symbols, number pad) is made the default by the system zip, for every app.
- **Power menu.** A hold on power opens VoidOS's menu. Android's dialog only comes back if VoidOS or its key service is missing, so there's always a way to power off.
- **Volume panel.** VoidOS's sliders. The keys never reach Android.
- **Lock screen (after the first unlock).** VoidOS's octagon-dial lock screen and its own PIN pad over Android's.
- **Restarting and Powering off.** VoidOS's own screen covers Android's grey "Shutting down" box.
- **Boot animation.** The boot zip's VoidOS lava-lamp logo.
- **Low battery warning, screenshot and screen recording.** VoidOS's own (no Android consent dialog).
- **Toasts and messages from VoidOS.** VoidOS's own chamfered note. VoidOS never calls Android's toast, snackbar, dialog, date picker, time picker or switch: the alarm time is VoidOS's own wheel, and every switch is the VoidOS octagon switch.
- **VoidOS's own permission prompts.** Granted up front by the system zip.
- **Guest Mode, Kids Space, user switching.** Blocked.

## Themed by the system zip's overlays

These are drawn by Android, so VoidOS can't draw them itself. Two static resource overlays in `/vendor/overlay` put VoidOS navy, teal and squarer corners on them, Manrope replaces Roboto as the system font, and dark mode is forced on.

- **Android dialogs** (the ones other apps raise, Bluetooth pairing, USB prompts): framework overlay `com.andysocial.voidos.theme` on `android`, priority 998.
- **Share sheet and "Open with".** Other apps open Android's chooser directly, so it can only be themed (framework overlay).
- **Other apps' permission prompts** ("Allow Spotify to..."). Shown by Android's permission controller for each app; themed by the framework overlay and dark mode.
- **Toasts from other apps.** Android draws them; themed colours and position.
- **Android Settings** ("All settings" and deeper links from VoidOS's System page). A separate app that a home app can't redraw; it picks up the dark palette, teal accent and Manrope.
- **First unlock after a restart.** The storage is encrypted and only Android's own lock screen can unlock it, before VoidOS can start. Themed by the SystemUI overlay `com.andysocial.voidos.theme.sysui` (priority 998) and Manrope.

## Switched off

- **"App isn't responding" and crash dialogs.** Hidden by the system zip (`hide_error_dialogs 1`). A frozen app can still be closed from VoidOS's app switcher.
- **"Viewing full screen" and accessibility shortcut dialogs.** Marked as already seen.

## Can't be changed on this Android 10 Lenovo

- **Recovery, fastboot and Magisk safe mode.** They run before Android starts.
- **Android's grey shutdown box.** Android draws it itself while shutting down. VoidOS's screen sits on top, but it can flash for a moment.
- **Apps' own screens.** Anything inside Spotify, Chrome and so on is the app's own design.

## How the overlays are built and switched on

- Three static overlay APKs, built with aapt2 and signed with VoidOS's key: `VoidOSNoBar` (priority 999), `VoidOSTheme` and `VoidOSThemeSysUI` (priority 998). They target different resources, so the priorities never compete.
- Magisk mounts them at `/system/vendor/overlay/<Name>/<Name>.apk`, which is `/vendor/overlay` on this tablet, where Android 10 looks for preinstalled overlays.
- Static overlays are switched on by Android at every boot. `service.sh` also runs `cmd overlay enable` for each one as a fallback and writes their state to `/data/local/tmp/voidos_overlays.txt`. To check on the tablet: `adb shell cat /data/local/tmp/voidos_overlays.txt` (each line should start with `[x]`).
- A colour or size name that this build of Android doesn't have is ignored, so the overlays can't break a screen; at worst a surface keeps a stock colour.
