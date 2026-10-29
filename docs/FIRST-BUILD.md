# First build on GitHub (no Android SDK, no Android Studio)

Everything runs on GitHub's servers with Google's official SDK tools. You only use a web browser.

## 1. Put the code on GitHub

Create a repository (for example `free-my-fire-tv`) and upload/push this project. The workflow
`.github/workflows/build.yml` starts by itself on every push to `main`, every pull request and every tag `v*`; you can
also start it by hand: **Actions > Build APK > Run workflow**.

## 2. Get the APK

Open the finished run (green tick) > **Artifacts** > `FreeMyFireTV-apk` (a zip with `FreeMyFireTV.apk` and
`SHA256SUMS`). The run's summary page shows the SHA-256, the signer certificate and the package data. Artifacts are
kept 90 days.

Without the secrets below, the APK is signed with a **temporary key** (the summary says so). It installs fine, but you
cannot update it with an APK from a later run: uninstall first. Do step 3 before you keep using it.

## 3. Create the signing key once (so updates and releases work)

The private key must never be committed. Make it once on any machine with Java (for example a free
[GitHub Codespace](https://github.com/features/codespaces), where `keytool` is already installed):

```
keytool -genkeypair -v -keystore release.keystore -alias freemyfiretv \
  -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 release.keystore > release.keystore.b64      # macOS: base64 -i release.keystore -o release.keystore.b64
```

`keytool` asks for a password and some name fields (any values). **Keep `release.keystore` and the password in a
password manager**: lose them and nobody can ever update installed copies. Then, in the repository: **Settings >
Secrets and variables > Actions > New repository secret**:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the content of `release.keystore.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `freemyfiretv` (optional if there is only one key) |
| `ANDROID_KEY_PASSWORD` | the key password (optional if it equals the keystore password) |

Delete `release.keystore` and the `.b64` file from any shared/temporary place afterwards (they are in `.gitignore`).
Pull requests from forks never receive secrets, so they always use a temporary key.

## 4. Publish a release

Raise `android:versionCode` (every release needs a higher number) and `versionName` in `app/AndroidManifest.xml`, commit,
then tag:

```
git tag v0.1.0 && git push origin v0.1.0
```

The workflow builds, signs with your key, and creates the GitHub release with `FreeMyFireTV.apk` and `SHA256SUMS`.
**A tag build signed with a temporary key is not published as a release** (the release job is skipped), so a release can
never carry a throwaway key.

## 5. Install

See the README: `adb connect <IP>:5555`, then `adb install -r FreeMyFireTV.apk`.

## If the build fails

Open the failed step in the run log. Typical causes: a Java compile error (the log shows the file and line); a
translation catalogue out of date (`python3 tools/translate.py` locally; new strings must exist in every language);
the runner image no longer shipping the SDK command-line tools (the workflow then downloads them from dl.google.com).
This workflow has not been run yet: the first run is the first real test of the build, so expect to fix small things.
