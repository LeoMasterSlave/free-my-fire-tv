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

## Differences from Android TV (Free My TV)
- No "replace the home screen" mode, no disabling apps, no Continue Watching (needs the TV's ADB), no Now Playing listener,
  no remote-button service, no self-updater. Those rows show *Unavailable*.
- Vega OS (not Android) is unsupported.

## Privacy
Network: TMDB (picks, optional own key), Open-Meteo, Binance (if the crypto widget is on). The self-updater of the
original is compiled out (`Updates.ENABLED = false`). No analytics, no ads.
