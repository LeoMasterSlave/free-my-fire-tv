# Tests that need a physical Fire TV Stick

None of these has been run yet. Until they are, **nothing here counts as working**. Log with
`adb logcat -s FreeMyFireTV` (the redirect logs `Amazon home arrived (...): VERDICT`). Please report results as: model,
Fire OS version (Settings > My Fire TV > About), what you saw.

What *has* run (no device needed): the Home redirect decision rules (`tests/RedirectPolicyTest.java`, 39 checks), the
translation catalogues check, and a compile check against the API 28, 30 and 34 `android.jar`s (the new Fire code uses
nothing newer than API 28). The full APK build is first run by GitHub Actions.

## A. Install and launch
- [ ] A1 `adb install -r FreeMyFireTV.apk` succeeds on Fire OS 7 (Android 9) and Fire OS 8 (Android 11).
- [ ] A2 The icon appears among the apps (Fire TV 'Your Apps') and opens the home screen directly.
- [ ] A3 Home screen draws smoothly (clock, weather, shelves, wallpapers) on a standard stick; note any stutter, and
  free memory (`adb shell dumpsys meminfo io.freemyfiretv`).
- [ ] A4 Every remote button: arrows, OK, Back, Menu (≡ opens Settings), Play/Pause, long-press OK.
- [ ] A5 Apps listed and launched: Netflix, Prime Video, YouTube, Disney+, a sideloaded app. Apps whose launch intent is
  Fire-specific.
- [ ] A6 'Amazon Home' tile opens Amazon's home.
- [ ] A7 Sleep Timer (kept from Free My TV) works, and Home Settings screens don't crash.

## B. Home button redirect (the unverified core)
- [ ] B1 Settings > Home Button > Set up: which path worked (already / WRITE_SECURE_SETTINGS / local ADB / manual)?
  Does the TV show *Allow USB debugging?* for 127.0.0.1? Is port 5555 reachable from the app at all on Fire OS 7 and 8?
- [ ] B2 After `pm grant` from a computer, does setup finish by itself and show *Ready: press Home to test*?
- [ ] B3 Press Home from inside an app: does Free My Fire TV open? How long after (Fire OS 7: expected ~5 s)? Does Amazon's
  home flash first? Does the status become *Working*?
- [ ] B4 Press Home while on Amazon's home (already in front): nothing odd happens.
- [ ] B5 Press Home twice quickly: Amazon's home stays (escape works), redirect resumes after ~20 s.
- [ ] B6 Back from an app launched from our home returns to our home, not to Amazon's.
- [ ] B7 Does Fire OS block the background `startActivity` from the service (log: "couldn't start Free My Fire TV's home
  screen")? If so, record it: the app then stays a manual launcher.
- [ ] B8 Loop protection: if ours does not stay up, redirects stop (4 in 10 s, then 1 minute pause).
- [ ] B9 Reboot the stick: is the service still enabled and working? After `adb install -r` of a new version: is it
  re-enabled by the app (repair), or do you need to run Set up again?
- [ ] B10 Alexa voice / long-press Home / Settings key shortcuts still behave as Amazon intended.
- [ ] B11 Kids profile / a second profile: behaviour.

## C. Restore
- [ ] C1 Settings > Home Button > Restore Amazon Home: Home button is Amazon's again; the service is gone from
  Settings > Accessibility; `adb shell settings get secure enabled_accessibility_services` has no `io.freemyfiretv` entry.
- [ ] C2 Manual restore commands from the README work.
- [ ] C3 `adb uninstall io.freemyfiretv` leaves the stick as before (no accessibility entry, Home is Amazon's).
- [ ] C4 No `com.amazon.*` package was disabled: `adb shell pm list packages -d` is unchanged.

## D. Things that should NOT work (confirm they are cleanly unavailable)
- [ ] D1 Settings rows for Continue Watching / Now Playing / Remote Buttons / Updates say *Unavailable* with an explanation, and don't crash.
- [ ] D2 'Free My Fire TV mode' switch is refused on Fire OS and nothing was turned off.
