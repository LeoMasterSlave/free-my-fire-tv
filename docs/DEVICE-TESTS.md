# Tests that need a physical Fire TV Stick

Results so far come from one device: a Fire TV Stick on **Fire OS 7.7.1.7** (reported by its owner, who answered in
words, not logs). Anything not listed under "Results" below has **not** been run and does not count as working.

## Results
- Fire OS 7.7.1.7 (Fire TV Stick, exact model not recorded): A1 install OK; A2/A3 home opens and is smooth; B1/B3 setup
  ends in *Working*; Home from an app opens Free My Fire TV after about 1 s, with Amazon's home visible first.
  The owner also reported the other checks asked for (power-cycle, Back from an app, double Home) "work", without
  details: treat those as unconfirmed until logs or specifics are added.
- Power-on straight into Free My Fire TV (0.1.1, `HomeRedirectService.bootStart`): to be tested.
- Fire OS 8: not tested.

Original list: Log with
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
- [ ] B9 Reboot the stick (0.1.1: does our home show by itself within a few seconds of Amazon's? how long?): is the service still enabled and working? After `adb install -r` of a new version: is it
  re-enabled by the app (repair), or do you need to run Set up again?
- [ ] B10 Alexa voice / long-press Home / Settings key shortcuts still behave as Amazon intended.
- [ ] B11 Kids profile / a second profile: behaviour.

## C. Restore
- [ ] C1 Settings > Home Button > Restore Amazon Home: Home button is Amazon's again; the service is gone from
  Settings > Accessibility; `adb shell settings get secure enabled_accessibility_services` has no `io.freemyfiretv` entry.
- [ ] C2 Manual restore commands from the README work.
- [ ] C3 `adb uninstall io.freemyfiretv` leaves the stick as before (no accessibility entry, Home is Amazon's).
- [ ] C4 No `com.amazon.*` package was disabled: `adb shell pm list packages -d` is unchanged.

## E. Hide Amazon's Home (0.2.0, experimental)
Known on Fire OS 7.7.1.7: `pm enable com.amazon.tv.launcher` -> `Cannot disable a protected package`. E1 is expected to fail there; the rest is for other versions.
- [ ] E1 Settings > Home Button > Hide Amazon's Home: after two OKs, does it end with "Amazon's Home is off"? What does
  `adb shell pm list packages -d` show, and `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`?
- [ ] E2 Press Home from an app: our home opens at once, Amazon's never shows.
- [ ] E3 Power-cycle: does the stick come up straight into our home?
- [ ] E4 TV Settings (My Fire TV) and the drawer shortcuts (Network, Display & Sounds, Apps...) open and work; what else
  is broken (quick settings, notifications, Alexa/voice button, Back from apps)?
- [ ] E5 Bring Amazon's Home back (Settings, and manual `adb shell pm enable com.amazon.tv.launcher`): Home is Amazon's again.
- [ ] E6 After an update of the app (`adb install -r`), a reboot, and (if possible) a Fire OS update: state of the launcher.

## D. Things that should NOT work (confirm they are cleanly unavailable)
- [ ] D1 Settings rows for Continue Watching / Now Playing / Remote Buttons / Updates say *Unavailable* with an explanation, and don't crash.
- [ ] D2 'Free My Fire TV mode' switch is refused on Fire OS and nothing was turned off.
