#!/bin/bash
set -euo pipefail
D=$1; LABEL=$2
# Instrumentation uses a debug target so release signing secrets are never needed in PRs.
adb uninstall com.aly.sahltv >/dev/null
adb install -r -g "$D/app/build/outputs/apk/debug/app-debug.apk"
adb install -r "$D/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
timeout --kill-after=5s 180s adb shell am instrument -w -r com.aly.sahltv.test/android.test.InstrumentationTestRunner | tee "instrumentation-$LABEL.txt"
# am instrument can exit zero for failed tests or runner crashes. Require the success footer.
grep -Eq '^OK \([1-9][0-9]* tests?\)' "instrumentation-$LABEL.txt"
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed' "instrumentation-$LABEL.txt"
