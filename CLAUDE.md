# Free My Fire TV: notes for Claude

A home screen for Amazon Fire TV Sticks (Fire OS 7 = Android 9, Fire OS 8 = Android 11), forked from Free My TV (MIT,
simbasjungles). A plain Android app in Java, built without Gradle by `build.sh`; CI is `.github/workflows/build.yml`.
Read `README.md` and `docs/` first. Keep the person's wording simple: they may not be technical.

## Always

- **Never claim the Home redirect works until it was seen on a real Fire TV.** Everything that needs a stick is listed in
  `docs/DEVICE-TESTS.md`; mark results from a stick explicitly, and keep unverified things labelled as unverified.
- Amazon's launcher keeps the Home button. No root, no exploit, nothing irreversible, and **no `com.amazon.*` package is
  ever disabled** (`HomeMode.AMAZON` refuses the replace-the-launcher mode; `mayTurnOff`). Never promise a permission or
  ADB step is enough before its result was verified (`FireHome.setupNow` checks `checkSelfPermission`/the setting).
- The way back must always work: Settings > Home Button > Restore Amazon Home (`FireHome.restoreNow`), double Home
  (`RedirectPolicy` escape), the Amazon Home tile, `docs`/README manual commands, uninstall.
- Licence: MIT. **No GPLv3 code from Home on Fire** (`toolicious/home-on-fire`) may be copied or translated: the
  accessibility service is original. Keep Free My TV's copyright notice and credits in `LICENSE` and the README.
- Never commit `config.properties`, `*.keystore`, `*.jks` or any key. Signing keys come from repository secrets only;
  CI builds with `./build.sh --generic` (no TMDB key built in). Releases must not use the temporary key.
- After any change `./build.sh --generic` must end with `Built FreeMyFireTV.apk`; run `tools/check.sh` before pushing
  (translations + `tests/RedirectPolicyTest.java` need only Python and a JDK).

## The Fire-specific code

- `RedirectPolicy` (pure Java, unit-tested): when a window event is a Home press that should be redirected, plus the
  escape / dedupe / loop-breaker rules and the pure helpers for the `enabled_accessibility_services` setting.
- `HomeRedirectService`: accessibility service, `TYPE_WINDOW_STATE_CHANGED` only, no content, no key filter. Starts
  `FireHomeActivity` (the home screen as a normal app, `:home` process). Logs to `adb logcat -s FreeMyFireTV`.
- `FireHome`: state files (`fire-home.json`, `fire-redirect.json`, `fire-setup.json`), guided setup (already enabled ->
  `WRITE_SECURE_SETTINGS` -> the TV's own ADB -> manual command), restore, post-update repair, `Snapshot` for the UI.
  `ModeJob` runs setup/restore in the background. Settings screen `FIRE_HOME` in `SettingsActivity`.
- The app icon opens the home screen directly on Fire (`SettingsActivity.openFireHome`); the gear and the remote's Menu
  key open Settings. `Updates.ENABLED = false` (updates are by adb). Vega OS is unsupported.

## The code (inherited from Free My TV)

- Java 8 language level, minSdk 26, targetSdk 33, no libraries. Home drawn by hand on one Canvas (`HomeView`): no
  allocations in `onDraw`, keep 60 fps. On API < 31 `Ui.LEGACY` skips GPU layers.
- To check APIs, compile against `platforms/android-28/android.jar`: only `RuntimeShader`, `BitmapShader` filter modes and
  `RenderNode` (all guarded) should come up.
- On-screen text: `Ui.tr("English")`; run `python3 tools/translate.py` after changing text, add Italian (hand) and keep
  placeholders; other languages need every key too (`--fill` with `ANTHROPIC_API_KEY`, or English as a stopgap).
  `python3 tools/translate.py --check` must pass (CI runs it).
- Comments in English, short, only where the why isn't obvious.
