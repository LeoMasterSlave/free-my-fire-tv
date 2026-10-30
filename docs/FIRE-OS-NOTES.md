# Fire OS notes

Facts below are marked **checked** (done here, no device needed) or **assumed** (needs a stick: see DEVICE-TESTS.md).

## API availability (Fire OS 7 = API 28, Fire OS 8 = API 30)
- **checked:** the whole app was compiled against `android.jar` of API 28, 30 and 34. The Fire-specific code
  (`FireHome`, `HomeRedirectService`, `RedirectPolicy`, settings changes) uses only APIs present in API 28.
- **checked:** the only symbols missing below API 31/33 are in code inherited from Free My TV and used behind version
  checks / try-catch: `RuntimeShader` (AGSL, API 33), `BitmapShader.setFilterMode` (API 33), `RenderNode` (API 29; Android 9
  draws into a `Picture` instead). `PendingIntent.FLAG_MUTABLE` and `Context.RECEIVER_NOT_EXPORTED` are API 31/33
  constants that the code reads only behind `SDK_INT` checks.
- minSdk 26, targetSdk 33. Fire OS 7/8 report Android 9/11; `targetSdk 33` means Android 14+ behaviour changes apply
  only on newer devices.

## Performance on a standard stick
- On Android 8-11 the home screen uses `Ui.LEGACY`: no GPU layers, cached bitmap shadows, image rounding through
  shaders (inherited from Free My TV, made for Mali-450-class GPUs). **assumed** adequate on Fire sticks; measure (A3).
- Low-RAM devices (`ActivityManager.isLowRamDevice`) show at most 8 apps on the shelf (`HomeApps.FEW_APPS`). The home screen runs in its own
  process (`:home`) so the redirect service in the main process stays small.
- The accessibility service only listens to `TYPE_WINDOW_STATE_CHANGED` with no content retrieval and no key
  filtering, to add no latency to the remote's keys.

## Redirect design
- Amazon's launcher stays the system home (it declares Home with priority 950; the app's `HomeMode.AMAZON` check
  refuses the "replace the launcher" mode, so no package is disabled).
- Arrival of Amazon's home window after another package = a Home press (or Back out of an app). The launcher is looked
  up from the system (`MAIN` + `HOME` intents) instead of hard-coding a package.
- Safety nets (`RedirectPolicy`): double-Home escape, dedupe while our start is pending (Fire OS 7), loop breaker,
  user pause, global switch, watchdog after 3 crashes.
- **assumed, unverified:** that the window events look like this on real Fire OS 7/8; that a background
  `startActivity` from an accessibility service is permitted (Android 10+ restricts background starts, but apps with an
  enabled accessibility service are normally exempt; Fire OS 8 is Android 11); that `pm grant WRITE_SECURE_SETTINGS`
  works through the stick's own ADB loopback.
- Fire OS 7 delays apps started from the Home button (app-switch lock): expected ~5 s lag. Fire OS 8 does not.

## Observed on a real stick (Fire OS 7.7.1.7)
- Amazon's home window: `com.amazon.tv.launcher/com.amazon.tv.launcher.ui.HomeActivity_vNext` (matches `isAmazonHome`).
- `android.settings.SETTINGS` resolves to `com.amazon.tv.launcher/.ui.SettingsActivity`; Amazon's settings screen has
  `HomeActivity_vNext` focused. Opening it from our home therefore looked like Amazon's home arriving and the
  redirect sent the person back: "TV Settings" now pauses the redirect for 15 s first (`FireHome.SETTINGS_PAUSE_MS`).
  Since 0.1.4 that pause starts a *settings session* (`RedirectPolicy.Kind`): while Amazon's settings windows keep
  appearing the redirect stays off (up to 30 min), and it ends at the first window that isn't Amazon's (our home, an app).
- The stick has no tuner and no HDMI inputs (the owner uses it on a monitor): the Live TV and HDMI Inputs buttons are hidden when there is no tuner input and no HDMI input (`HomeApps.liveTv`, `topButtons`). Amazon's Live TV app is installed anyway, so its presence is not a test.
- Installed settings packages include `com.amazon.tv.settings.v2` and a stub `com.android.settings`.

## Cover (0.3.0)
`HomeRedirectService` adds a full-screen black `TYPE_ACCESSIBILITY_OVERLAY` window (not focusable, not touchable) when the
verdict is REDIRECT, and removes it 250 ms after our home activity's window appears, after 4 s at most, or when the
redirect is cancelled (escape, pause, loop breaker). Events of our own overlay are ignored so they aren't read as our home.
**Not yet tried on a stick.**

## Hide Amazon's Home (0.2.0)
`FireReplace` runs, through the TV's own ADB: `cmd package set-home-activity io.freemyfiretv/.HomeActivity` (must answer
"Success"), writes the way back to `fire-replace.json`, `pm disable-user --user 0 com.amazon.tv.launcher`, then checks
that the launcher is listed disabled (`pm list packages -d`) and that HOME resolves to this app, else undoes everything.
Undo = `pm enable` + `set-home-activity <recorded original home>`. Amazon's settings pages are listed from the stick's
own dump of `com.amazon.tv.settings.v2` (DeviceActivity, NetworkActivity, ...). **Observed on Fire OS 7.7.1.7:** `pm enable com.amazon.tv.launcher` answers `SecurityException: Cannot disable a protected
package` (PackageManagerService.setEnabledSetting): the launcher is protected, so turning it off is expected to fail there.
The full feature has not been run on a stick.

## Remove extra Amazon apps (0.3.1)
`FireDebloat`: a per-package `pm disable-user --user 0 <pkg>` / `pm enable <pkg>` through the TV's own ADB (same
mechanism as `FireReplace`, one package at a time, from Settings > Home Button > Remove extra Amazon apps). The list
offered is read from the TV itself (`PackageManager.getInstalledApplications`, every `com.amazon.*` package found,
minus a short deny-list: the launcher, the Appstore, settings, OTA updater, and a few others the app or the system
depends on) rather than copied from a debloat guide, since such guides are often written for an older device or Fire
OS version. Nothing is assumed safe: the person sees the real package name (and its app label, when Android has one)
and decides. State is in `fire-debloat.json` (the set of packages this app turned off); only those are ever offered
`pm enable` back. **Not yet tried on a stick: whether disabling a given package breaks something else on this Fire OS
version is unknown until tested, one package at a time.**

## Differences from Android TV (Free My TV)
- No "replace the home screen" mode, no disabling apps, no Continue Watching (needs the TV's ADB), no Now Playing listener,
  no remote-button service, no self-updater. Those rows show *Unavailable*.
- Vega OS (not Android) is unsupported.

## Privacy
Network: TMDB (picks, optional own key), Open-Meteo, Binance (if the crypto widget is on). The self-updater of the
original is compiled out (`Updates.ENABLED = false`). No analytics, no ads.
