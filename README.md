# Free My Fire TV

An ad-free home screen for **Amazon Fire TV Sticks** (Fire OS 7 = Android 9, Fire OS 8 = Android 11): your favourite
apps, film and show picks, weather, clock and wallpapers, with no sponsored rows. It is a fork of
[Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT) by simbasjungles, keeping its look, animations and
remote-control feel.

> **Read this first: status.** The app builds from source with GitHub Actions, and its decision logic is unit-tested.
> **Nothing has yet been tested on a physical Fire TV Stick.** Whether the Home button can be redirected on your stick
> is exactly what is unverified, and it depends on Fire OS. If it does not work, Free My Fire TV still works as an app
> you launch yourself. The list of what needs a real stick is in [docs/DEVICE-TESTS.md](docs/DEVICE-TESTS.md).

Italiano: [README.it.md](README.it.md)

## How it works on a Fire TV

Amazon keeps its own launcher as the system's home screen. This app does **not** replace it, does not use root, and
turns nothing off (no `com.amazon.*` package is ever disabled). Instead:

1. **Launch it by hand:** the *Free My Fire TV* icon in your apps opens the home screen. This always works.
2. **Optional Home-button redirect:** a small accessibility service watches for Amazon's home screen coming to the
   front (window changes only: it reads no screen content and no key presses) and then opens Free My Fire TV on top.
   Where Fire OS lets this happen, pressing Home opens Free My Fire TV.

The redirect idea is inspired by [Home on Fire](https://github.com/toolicious/home-on-fire) (GPLv3). **No code from
that project is used here**; the service was written independently for this project, which stays MIT.

## Install

You need the stick's IP address (Settings > My Fire TV > About > Network) and *ADB debugging* on
(Settings > My Fire TV > Developer Options; if hidden, press OK 7 times on *About > Fire TV Stick* first).

1. Download `FreeMyFireTV.apk` from the [Releases](../../releases) page, or from the latest successful run in the
   **Actions** tab (artifact `FreeMyFireTV-apk`). No build tools are needed on your side.
   Never built one? See [docs/FIRST-BUILD.md](docs/FIRST-BUILD.md).
2. On a computer on the same Wi-Fi, with [adb](https://developer.android.com/tools/releases/platform-tools):
   ```
   adb connect <IP>:5555        # accept "Allow USB debugging?" on the TV, tick "Always allow"
   adb install -r FreeMyFireTV.apk
   ```
3. Open *Free My Fire TV* from your apps. From its home screen, the gear opens the settings, and so does the remote's
   Menu (≡) key.

A build from a run **without** a project signing key uses a throwaway key: it installs, but a later build cannot
update it (uninstall first). Releases are signed with the project's key. See FIRST-BUILD.

## Home button setup (optional)

On the TV: **Settings > Home Button > Set up the Home button.** The app tries these, in order, and tells you which one
worked and what it could verify:

1. the service is already enabled;
2. the app already holds the `WRITE_SECURE_SETTINGS` permission;
3. the TV's own ADB (`127.0.0.1:5555`): the TV asks *Allow USB debugging?* once. The result is checked afterwards, not
   assumed;
4. otherwise it opens Accessibility settings and shows the exact command to run from a computer:
   ```
   adb shell pm grant io.freemyfiretv android.permission.WRITE_SECURE_SETTINGS
   ```

The status line says **Working** only after a real redirect has happened. *Ready: press Home to test* means the
service runs but no redirect has been seen yet. **Whether a permission or ADB step is enough on your Fire OS version
is not guaranteed**; that is verified only by the result shown on the TV.

## Limitations

- **Unverified on real hardware** (see top). Behaviour of Amazon's launcher windows is assumed, not observed.
- **Fire OS 7** holds back apps started in the background: the redirect can show about 5 seconds late.
- **Fire OS 8** may flash Amazon's home for a moment before Free My Fire TV appears.
- If Amazon's home is already in front when you start the service, nothing happens until the next time it arrives.
- Press **Home twice quickly** to reach Amazon's home (redirect pauses for 20 s). If Free My Fire TV ever fails to stay
  up, the redirect pauses itself for a minute; after 3 crashes in 5 minutes it switches itself off.
- **Vega OS** sticks (newer Fire TV Sticks, not Android) are not supported.
- Shown as *Unavailable* on Fire TV in this version: Continue Watching, Now Playing, the remote-button service and
  self-updates. The Sleep Timer is kept but has not been tested on a stick.
- No automatic updates: download the new APK and `adb install -r` it.
- Android turns accessibility services off when an app is updated; the app re-enables it when it holds the permission
  and you had set it up (check after updating).
- Profiles/kids profiles, and apps that cover the screen, may behave differently. Report what you see.

## Restore Amazon's Home (always possible)

- **On the TV:** Settings > Home Button > **Restore Amazon Home**. It switches the service off and removes it from the
  enabled accessibility services.
- **Press Home twice quickly** to reach Amazon's home at once.
- **Home screen tile:** *Amazon Home* in the app drawer.
- **From a computer**, if the app itself is stuck:
  ```
  adb shell settings put secure enabled_accessibility_services ""
  adb shell pm revoke io.freemyfiretv android.permission.WRITE_SECURE_SETTINGS
  ```
  (the first line clears all enabled accessibility services, including other apps'; to keep theirs, read the list with
  `adb shell settings get secure enabled_accessibility_services` and remove only the `io.freemyfiretv/...` entry)
- **Uninstall:** `adb uninstall io.freemyfiretv` or remove it in Settings > Applications. Nothing else is left changed.

## Privacy

Talks only to TMDB (picks; with your own key, optional), Open-Meteo (weather) and, if enabled, Binance. No
advertising, no analytics. The accessibility service reads only the name of the window that came to the front.
Self-updating is disabled. Details in [docs/FIRE-OS-NOTES.md](docs/FIRE-OS-NOTES.md).

## Build

GitHub Actions builds it with Google's official SDK tools (`.github/workflows/build.yml`). Locally: JDK 17, Android SDK
`build-tools;34.0.0` + `platforms;android-34`, then `./build.sh --generic`. Checks: `tools/check.sh`.

## Credits and licence

MIT, see [LICENSE](LICENSE). Based on **Free My TV**, copyright (c) simbasjungles and contributors; this fork keeps
that notice. Also credited as in the original: TMDB (this product uses the TMDB API but is not endorsed or certified
by TMDB), Open-Meteo, Material Symbols, Inter, and Project Nayuki's QR code generator (MIT). *Home on Fire* by
toolicious is an inspiration only; none of its code is included. Fire TV and Amazon are trademarks of Amazon.com, Inc.;
this project is not affiliated with Amazon.
