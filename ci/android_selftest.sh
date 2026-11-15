#!/usr/bin/env bash
# Runs on the CI Android emulator: installs the APK, starts the full app in self-test mode and
# checks speech recognition, translation, voice and the learning data all work; then checks the
# classic app starts too.  usage: android_selftest.sh FieldTranslator.apk
set -u
APK="$1"
PKG=app.fieldtranslator
fail() { echo "::error::[android] $*"; adb exec-out screencap -p > android-failure.png 2>/dev/null; exit 1; }

adb wait-for-device
adb shell settings put global package_verifier_enable 0 >/dev/null 2>&1
echo "installing $(du -h "$APK" | cut -f1) APK"
adb install -r -g "$APK" || fail "install failed"
adb shell pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null
adb logcat -c

adb shell am start -W -n $PKG/.WebActivity --ez selftest true || fail "could not start the app"
t0=$(date +%s)
result=""
while [ $(( $(date +%s) - t0 )) -lt 900 ]; do
  sleep 10
  log=$(adb logcat -d -s FTWEB:I AndroidRuntime:E 2>/dev/null)
  if echo "$log" | grep -q "FATAL EXCEPTION"; then echo "$log" | tail -40; fail "app crashed"; fi
  if echo "$log" | grep -q "FTSELF ready"; then [ -f android-main.png ] || adb exec-out screencap -p > android-main.png; fi
  result=$(echo "$log" | grep -E "FTSELF (RESULT|ERROR)" | tail -1)
  [ -n "$result" ] && break
done
adb logcat -d -s FTWEB:I | grep -E "FTSELF|rror" | tail -20
[ -n "$result" ] || fail "self-test did not finish in 15 minutes"
echo "$result" | grep -q "FTSELF ERROR" && fail "self-test error: $result"
json=${result#*FTSELF RESULT }
echo "::notice::[android] $json"
echo "$json" | grep -q '"translation":"[^"]*[Ss]tation' || fail "translation wrong: $json"
echo "$json" | grep -q '"number":"две тысячи двадцать пять"' || fail "number words wrong: $json"
echo "$json" | grep -qE '"transcript":"[^"]{4,}' || fail "Russian speech recognition returned nothing: $json"
echo "$json" | grep -qE '"voiceSeconds":[0-9]*\.?[0-9]*[1-9]' || fail "voice produced no audio: $json"

# Back button on the main screen must not crash
adb shell input keyevent KEYCODE_BACK; sleep 2

# the classic app (backup) must still start
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity || fail "classic app did not start"
sleep 40
adb exec-out screencap -p > android-classic.png
adb logcat -d -s AndroidRuntime:E | grep -q "FATAL EXCEPTION" && { adb logcat -d -s AndroidRuntime:E | tail -30; fail "classic app crashed"; }
echo "ANDROID SELFTEST PASSED"
