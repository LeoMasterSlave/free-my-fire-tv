#!/usr/bin/env bash
# Builds FreeMyFireTV.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs a JDK (17+) and the Android SDK command-line tools (build-tools 34+, platform android-34). See README.md.
#   ./build.sh             with config.properties (your city, your TMDB key)
#   ./build.sh --generic   without it, as the APK on GitHub's releases: no key, and the TV's time zone gives the city
set -euo pipefail
cd "$(dirname "$0")"
GENERIC=false
for arg in "$@"; do [ "$arg" = "--generic" ] && GENERIC=true; done

# --- Tools
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/build-tools" ]; then SDK="$d"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || { echo "Android SDK not found. Install it and set ANDROID_HOME (see README)."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -f "${AJ:-}" ] || { echo "Android platform not found. Run: sdkmanager \"platforms;android-34\""; exit 1; }
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || { echo "JDK not found. Install JDK 17 and set JAVA_HOME."; exit 1; }

# --- Settings: config.properties -> Config.java
CONFIG=config.properties
if $GENERIC; then
  CONFIG=/dev/null   # every value at its default; the place comes from the TV's time zone (Place)
elif [ ! -f config.properties ]; then
  cp config.example.properties config.properties
  echo "Created config.properties: add your TMDB key and your city there (optional), then build again."
fi
# Notepad's "Unicode" is UTF-16: grep would find no keys in it and build every default without a word.
case "$(head -c 2 "$CONFIG" 2>/dev/null | od -An -tx1 | tr -d ' \n')" in
  fffe|feff) echo "config.properties is saved as UTF-16 (Notepad's \"Unicode\"). Save it as UTF-8 and build again." >&2; exit 1 ;;
esac
prop() {
  local v
  v="$(grep -E "^[[:space:]]*$1[[:space:]]*=" "$CONFIG" | tail -1 | cut -d= -f2- || true)"
  v="$(printf '%s' "$v" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | tr -d '"\\')"
  printf '%s' "${v:-$2}"
}
number() {
  local v; v="$(prop "$1" "$2")"
  [[ "$v" =~ ^-?[0-9]+(\.[0-9]+)?$ ]] || { echo "config.properties: $1 must be a number (got \"$v\")" >&2; exit 1; }
  printf '%s' "$v"
}
if $GENERIC; then
  LAT=""; LON=""; REGION=""; CURRENCY=""
else
  LAT="$(number latitude 40.7128)"
  LON="$(number longitude -74.0060)"
  REGION="$(prop tmdb_region US)"
  CURRENCY="$(prop currency USD)"
fi
ASSIST=false; [ "$(prop disable_assistant false)" = "true" ] && ASSIST=true
CRYPTO=false; [ "$(prop show_crypto true)" = "true" ] && CRYPTO=true
ANIMATION=false; [ "$(prop hide_animation false)" = "true" ] && ANIMATION=true
SAVER=true; [ "$(prop screensaver true)" = "false" ] && SAVER=false

# Servers on your own network (Immich, and the next feature that needs one) often answer in plain HTTP. One rule for
# all of them: a server given as http:// must be on the local network, and only its host may skip HTTPS
# (network_security_config.xml, below). A new one is read with `lan_server <key>`, then `prop <key>`.
LAN_HTTP_HOSTS=""
lan_host() {   # a private IPv4 address, or a .local, .lan or .home.arpa name, or a one-word name
  local h a b
  h="$(printf '%s' "$1" | tr 'A-Z' 'a-z')"
  if [[ "$h" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}$ ]]; then
    a=$((10#${BASH_REMATCH[1]})); b=$((10#${BASH_REMATCH[2]}))
    [ $a -eq 10 ] || { [ $a -eq 172 ] && [ $b -ge 16 ] && [ $b -le 31 ]; } || { [ $a -eq 192 ] && [ $b -eq 168 ]; }
    return
  fi
  [[ "$h" =~ ^[a-z0-9-]+$ || "$h" =~ ^[a-z0-9.-]+\.(local|lan|home\.arpa)$ ]]
}
lan_server() {   # lan_server <key>: checks it; the host of an http:// one joins LAN_HTTP_HOSTS
  local v host
  v="$(prop "$1" '')"
  case "$v" in
    "" | https://*) ;;
    http://*)
      host="$(printf '%s' "$v" | sed -E -e 's#^http://##' -e 's#[/?].*$##' -e 's#:[0-9]+$##' | tr 'A-Z' 'a-z')"
      lan_host "$host" || {
        echo "config.properties: $1 in plain http:// must be on your own network (a private address like" >&2
        echo "192.168.1.20, or a .local, .lan or .home.arpa name); use https:// for anything else (over Tailscale, the" >&2
        echo "server's https://<machine>.<tailnet>.ts.net address). Got \"$v\"" >&2
        exit 1
      }
      case " $LAN_HTTP_HOSTS " in *" $host "*) ;; *) LAN_HTTP_HOSTS="$LAN_HTTP_HOSTS $host" ;; esac
      ;;
    *) echo "config.properties: $1 must start with http:// or https:// (got \"$v\")" >&2; exit 1 ;;
  esac
}
lan_server immich_server
# Keys that would let another app on the TV into the user's own servers stay out of the APK: tools/send_key.py sends
# them to the TV (Secrets).
if [ -n "$(prop immich_api_key '')" ]; then
  echo "config.properties: immich_api_key isn't built into the app (any app on the TV could read it there)." >&2
  echo "Send it to the TV instead (python3 tools/send_key.py <TV IP> immich_api_key), then remove it here." >&2
fi

rm -rf out && mkdir -p out/gen/io/freemyfiretv out/classes
cat > out/gen/io/freemyfiretv/Config.java <<EOF
package io.freemyfiretv;

/** Generated by build.sh from config.properties: edit that file, not this one. */
final class Config {
    private Config() {}

    static final String TMDB_KEY = "$(prop tmdb_key '')";
    static final String WATCHMODE_KEY = "$(prop watchmode_key '')";
    static final String PLEX_URL = "$(prop plex_url '')";
    static final String PLEX_TOKEN = "$(prop plex_token '')";
    static final String TRAKT_CLIENT_ID = "$(prop trakt_client_id '')";
    static final String TMDB_REGION = "$REGION";
    static final String LATITUDE = "$LAT";
    static final String LONGITUDE = "$LON";
    static final String CITY = "$(prop city '')";
    static final String LANGUAGE = "$(prop language auto)";
    static final String CURRENCY = "$CURRENCY";
    static final String TEMPERATURE = "$(prop temperature auto)";
    static final String HIDE_SERVICES = "$(prop hide_services '')";
    static final String THEME = "$(prop theme auto)";
    static final String IMMICH_SERVER = "$(prop immich_server '')";
    static final boolean DISABLE_ASSISTANT = $ASSIST;
    static final boolean SHOW_CRYPTO = $CRYPTO;
    static final boolean HIDE_ANIMATION = $ANIMATION;
    static final boolean SCREENSAVER = $SAVER;   // false: the TV keeps its own
    static final boolean GENERIC = $GENERIC;   // built with --generic, as the APK on GitHub's releases (Updates)
}
EOF

# HTTPS everywhere, except the hosts lan_server let through (servers on your own network, in plain http://)
mkdir -p out/res/xml
{
  echo '<?xml version="1.0" encoding="utf-8"?>'
  echo '<!-- Generated by build.sh from config.properties: edit that file, not this one. -->'
  echo '<network-security-config>'
  echo '    <base-config cleartextTrafficPermitted="false" />'
  if [ -n "$LAN_HTTP_HOSTS" ]; then
    echo '    <domain-config cleartextTrafficPermitted="true">'
    for host in $LAN_HTTP_HOSTS; do echo "        <domain includeSubdomains=\"false\">$host</domain>"; done
    echo '    </domain-config>'
  fi
  echo '</network-security-config>'
} > out/res/xml/network_security_config.xml

# --- Build
"$BT/aapt2" compile --dir app/res -o out/res.zip
"$BT/aapt2" compile --dir out/res -o out/res-gen.zip
# Fonts uncompressed: Android reads them straight from the APK.
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest app/AndroidManifest.xml --java out/gen out/res.zip out/res-gen.zip \
  --min-sdk-version 26 --target-sdk-version 33 -A app/assets -0 ttf
# Windows aapt2 packs assets with backslash separators ("assets/symbols\x.png") and Android
# cannot find them: the home screen loses its icons and fonts. Rename the zip entries to "/".
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*)
    cat > out/FixAssets.java <<'EOF'
import java.nio.file.*;
import java.util.zip.*;

class FixAssets {
    public static void main(String[] args) throws Exception {
        Path apk = Paths.get(args[0]);
        Path tmp = apk.resolveSibling(apk.getFileName() + ".fixed");
        int renamed = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(apk));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                byte[] data = in.readAllBytes();
                String name = e.getName().replace('\\', '/');
                if (!name.equals(e.getName())) renamed++;
                ZipEntry n = new ZipEntry(name);
                if (e.getMethod() == ZipEntry.STORED) { // Android reads fonts straight from the APK
                    n.setMethod(ZipEntry.STORED);
                    n.setSize(data.length);
                    n.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    n.setCrc(crc.getValue());
                }
                out.putNextEntry(n);
                out.write(data);
                out.closeEntry();
            }
        }
        if (renamed > 0) Files.move(tmp, apk, StandardCopyOption.REPLACE_EXISTING);
        else Files.delete(tmp);
        System.out.println("FixAssets: renamed " + renamed + " asset paths in " + apk.getFileName());
    }
}
EOF
    "$JAVA" out/FixAssets.java out/base.apk
    ;;
esac
# On Windows javac separates paths with ";", and d8 and apksigner are .bat files. Git Bash can't start a .bat whose
# path has a space (and the wrappers trip over a JAVA_HOME that has one), so there the jars inside them are run with the
# JDK's java instead; on macOS and Linux the usual executables are used.
CPSEP=:; BAT=; case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) CPSEP=";"; BAT=.bat ;; esac
d8() {
  if [ -n "$BAT" ]; then "$JAVA" -Xmx1024M -Xss1m -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 "$@"
  else "$BT/d8" "$@"; fi
}
apksigner() {
  if [ -n "$BAT" ]; then "$JAVA" -jar "$BT/lib/apksigner.jar" "$@"
  else "$BT/apksigner" "$@"; fi
}
"$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ$CPSEP$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -encoding UTF-8 -d out/classes $(find app/src out/gen -name '*.java')
d8 --release --min-api 26 --lib "$AJ" --output out $(find out/classes -name '*.class')
if command -v zip >/dev/null; then (cd out && zip -qj base.apk classes.dex)
else "${JAVA_HOME:+$JAVA_HOME/bin/}jar" -u -M -f out/base.apk -C out classes.dex; fi # Git Bash has no zip
"$BT/zipalign" -f -p 4 out/base.apk out/aligned.apk

# --- Sign. Three ways, in this order:
#  1. A release/CI key from the environment (never stored in the repository):
#       FREEMYFIRETV_KEYSTORE=<keystore file>  FREEMYFIRETV_KEYSTORE_PASS=<store password>
#       optional: FREEMYFIRETV_KEY_ALIAS=<alias>  FREEMYFIRETV_KEY_PASS=<key password, default: the store password>
#  2. Your own key, created once next to this script (freemyfiretv.keystore). Keep it: updates must use the same key.
#  FREEMYFIRETV_TEMP_KEY=1 (CI without secrets) makes a throwaway key in out/: the APK installs, but can't be updated
#  over a later build, and the build says so.
if [ -n "${FREEMYFIRETV_KEYSTORE:-}" ]; then
  [ -f "$FREEMYFIRETV_KEYSTORE" ] || { echo "FREEMYFIRETV_KEYSTORE: no such file"; exit 1; }
  [ -n "${FREEMYFIRETV_KEYSTORE_PASS:-}" ] || { echo "FREEMYFIRETV_KEYSTORE_PASS is empty"; exit 1; }
  export FREEMYFIRETV_KEY_PASS="${FREEMYFIRETV_KEY_PASS:-$FREEMYFIRETV_KEYSTORE_PASS}"
  ALIAS_ARGS=()
  [ -n "${FREEMYFIRETV_KEY_ALIAS:-}" ] && ALIAS_ARGS=(--ks-key-alias "$FREEMYFIRETV_KEY_ALIAS")
  apksigner sign --ks "$FREEMYFIRETV_KEYSTORE" ${ALIAS_ARGS[@]+"${ALIAS_ARGS[@]}"} --ks-pass env:FREEMYFIRETV_KEYSTORE_PASS \
    --key-pass env:FREEMYFIRETV_KEY_PASS --out FreeMyFireTV.apk out/aligned.apk
  SIGNED_WITH="the key from FREEMYFIRETV_KEYSTORE"
elif [ "${FREEMYFIRETV_TEMP_KEY:-}" = 1 ]; then
  TMPPASS="$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  "$KEYTOOL" -genkeypair -keystore out/temp.keystore -alias temp -keyalg RSA -keysize 2048 -validity 3650 \
    -storepass "$TMPPASS" -keypass "$TMPPASS" -dname "CN=Free My Fire TV (temporary key)" >/dev/null 2>&1
  FREEMYFIRETV_TMP_PASS="$TMPPASS" apksigner sign --ks out/temp.keystore --ks-pass env:FREEMYFIRETV_TMP_PASS \
    --key-pass env:FREEMYFIRETV_TMP_PASS --out FreeMyFireTV.apk out/aligned.apk
  rm -f out/temp.keystore
  SIGNED_WITH="a TEMPORARY key (this APK can't be updated by a build signed with another key)"
else
  if [ ! -f freemyfiretv.keystore ]; then
    "$KEYTOOL" -genkeypair -keystore freemyfiretv.keystore -alias freemyfiretv -keyalg RSA -keysize 2048 -validity 20000 \
      -storepass freemyfiretv -keypass freemyfiretv -dname "CN=Free My Fire TV" >/dev/null 2>&1
  fi
  apksigner sign --ks freemyfiretv.keystore --ks-pass pass:freemyfiretv --key-pass pass:freemyfiretv --out FreeMyFireTV.apk out/aligned.apk
  SIGNED_WITH="your local key freemyfiretv.keystore (keep it for updates)"
fi
echo "Built FreeMyFireTV.apk ($(du -h FreeMyFireTV.apk | cut -f1)), signed with $SIGNED_WITH."
echo "Install: adb install -r FreeMyFireTV.apk"
