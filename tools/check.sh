#!/usr/bin/env bash
# What CI runs (.github/workflows/build.yml). Run it before you push:
#
#   tools/check.sh           # everything
#   tools/check.sh android   # only the APK build (needs what build.sh needs)
#   tools/check.sh tests     # translations and the Home redirect rules (needs only Python 3 and a JDK)
#
# One difference: here ./build.sh uses the newest SDK and JDK you have, while CI uses build-tools 34.0.0, android-34 and
# JDK 17. A call newer than Android 34 can pass here and fail on the pull request.
set -euo pipefail
cd "$(dirname "$0")/.."
what="${1:-all}"
case "$what" in
  all|android|tests) ;;
  *) echo "Usage: tools/check.sh [all|android|tests]" >&2; exit 2 ;;
esac

if [ "$what" = all ] || [ "$what" = tests ]; then
  python3 tools/translate.py --check
  rm -rf out/test
  mkdir -p out/test
  javac -d out/test app/src/io/freemyfiretv/RedirectPolicy.java tests/RedirectPolicyTest.java
  java -cp out/test io.freemyfiretv.RedirectPolicyTest
fi

if [ "$what" = all ] || [ "$what" = android ]; then
  ./build.sh --generic
fi

echo "All checks passed."
