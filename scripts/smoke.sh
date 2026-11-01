#!/usr/bin/env bash
# Smoke test run on an emulator by .github/workflows/smoke.yml.
# Installs the debug APK, launches the app, relaunches it (state restored from disk), then opens a few
# menus and screens. Any crash ("FATAL EXCEPTION") or a dead process fails the run, and the stack trace
# is printed so it shows up in the CI log.
set -u

PKG=com.boxweb.browser.debug
ACT=com.boxweb.browser.MainActivity
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=smoke-logs
mkdir -p "$OUT"
fail=0

adb install -r "$APK" || { echo "install failed"; exit 1; }

check() { # check <name> [seconds to wait first]
  sleep "${2:-6}"
  adb logcat -d > "$OUT/$1.log"
  if grep -q "FATAL EXCEPTION" "$OUT/$1.log" || [ -z "$(adb shell pidof $PKG | tr -d '\r')" ]; then
    echo "=== CRASH after step: $1 ==="
    grep -B2 -A45 "FATAL EXCEPTION" "$OUT/$1.log" | head -120
    fail=1
    return 1
  fi
  echo "=== OK: $1 ==="
  return 0
}

# Taps the first on-screen element whose text or content description equals $1.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  xy=$(python3 - "$1" <<'PY'
import sys, re
import xml.etree.ElementTree as ET
want = sys.argv[1]
try:
    root = ET.parse("smoke-logs/ui.xml").getroot()
except Exception:
    sys.exit(1)
for n in root.iter("node"):
    if n.get("text") == want or n.get("content-desc") == want:
        m = re.findall(r"\d+", n.get("bounds", ""))
        if len(m) == 4:
            l, t, r, b = map(int, m)
            print((l + r) // 2, (t + b) // 2)
            sys.exit(0)
sys.exit(1)
PY
  )
  if [ -n "$xy" ]; then
    adb shell input tap $xy
    return 0
  fi
  echo "(skip: '$1' not on screen)"
  return 1
}

back() { adb shell input keyevent KEYCODE_BACK; }

# 1. First launch
adb logcat -c
adb shell am start -W -n "$PKG/$ACT" >/dev/null
check launch 12

# 2. Relaunch: tabs and settings are restored from disk
adb logcat -c
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACT" >/dev/null
check relaunch 10

# 3. Open the overflow menu and a few of its screens
for item in "Settings" "Reading list" "Saved pages" "Bookmarks" "History" "Downloads"; do
  adb logcat -c
  if tap "More options"; then
    sleep 1
    if tap "$item"; then
      check "menu-$(echo "$item" | tr ' ' '_')" 3
      back
      sleep 1
      back
    else
      back
    fi
  fi
done
check after-menus 2

# 4. Tab list and a new tab
adb logcat -c
if tap "Open pages"; then
  check tab-list 3
  back
fi

# 5. A link from outside the app
adb logcat -c
adb shell am start -W -a android.intent.action.VIEW -d "https://example.com" -n "$PKG/$ACT" >/dev/null
check view-link 8

# 6. Relaunch once more, now with the tabs the run created
adb logcat -c
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACT" >/dev/null
check relaunch-2 10

exit $fail
