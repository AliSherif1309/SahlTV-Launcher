#!/bin/bash
# Performance test on an Android TV emulator (run by .github/workflows/perf.yml after each build).
# Measures instead of assuming:
#   1. start-up time of Sahl TV (cold, and return to Home), 5 runs each
#   2. memory used by Sahl TV
#   3. remote navigation: 80 D-pad presses across the home screen -> frames drawn, slow frames, crashes
#   4. system-wide am kill-all baseline: memory freed, how much is still free 1 minute later,
#      and how much slower the apps open afterwards (they must cold-start again)
# Usage: perf.sh <folder with the APKs> <label: RAM in MB, optionally -build>
set -u
D=$1; RAM=$2; OUT=perf-$RAM.md
PKG=com.aly.sahltv; ACT=$PKG/.MainActivity
A() { adb shell "$@" 2>/dev/null | tr -d '\r'; }
median() { sort -n | awk '{a[NR]=$1} END {if (NR==0) print "-"; else print a[int((NR+1)/2)]}'; }
start_ms() { A am start -W -n "$1" | awk -F': ' '/TotalTime/ {print $2}'; }
avail_mb() { A cat /proc/meminfo | awk '/MemAvailable/ {print int($2/1024)}'; }

adb wait-for-device
for i in $(seq 1 60); do [ "$(A getprop sys.boot_completed)" = "1" ] && break; sleep 2; done
for f in SahlTV SahlQuran SahlPDF SahlFiles; do adb install -r -g "$D/$f.apk" >/dev/null 2>&1 || echo "install $f failed"; done
A cmd package set-home-activity "$ACT" >/dev/null
A settings put global window_animation_scale 1; A settings put global transition_animation_scale 1
A am start -W -n "$ACT" >/dev/null; sleep 6          # first start: let it load apps, photos and caches

# 1) start-up
COLD=(); WARM=(); READY=()
for i in 1 2 3 4 5; do
  A am force-stop $PKG; sleep 3; adb logcat -c
  COLD+=("$(start_ms $ACT)"); sleep 4
  READY+=("$(adb logcat -d -s SahlPerf:I 2>/dev/null | tr -d '\r' | sed -n 's/.*ready=\([0-9]*\).*/\1/p' | head -1)")
done
for i in 1 2 3 4 5; do
  A am start -n com.android.tv.settings/.MainSettings >/dev/null 2>&1 || A am start -a android.settings.SETTINGS >/dev/null; sleep 3
  A input keyevent KEYCODE_HOME; sleep 1
  WARM+=("$(start_ms $ACT)"); sleep 2
done
COLD_MED=$(printf '%s\n' "${COLD[@]}" | grep -E '^[0-9]+$' | median)
WARM_MED=$(printf '%s\n' "${WARM[@]}" | grep -E '^[0-9]+$' | median)
READY_MED=$(printf '%s\n' "${READY[@]}" | grep -E '^[0-9]+$' | median)

# 2) memory
sleep 5
PSS=$(A dumpsys meminfo $PKG | awk '/TOTAL PSS:/ {print int($3/1024); exit} /^ *TOTAL:? / {print int($2/1024); exit}')
TOTAL_RAM=$(A cat /proc/meminfo | awk '/MemTotal/ {print int($2/1024)}')
LOWRAM=$(A getprop ro.config.low_ram)

# 3) remote navigation
adb logcat -c
A dumpsys gfxinfo $PKG reset >/dev/null
for r in 1 2; do
  for k in DOWN DOWN DOWN DOWN RIGHT RIGHT RIGHT RIGHT RIGHT DOWN LEFT LEFT LEFT LEFT DOWN RIGHT RIGHT UP UP UP; do
    A input keyevent KEYCODE_DPAD_$k; sleep 0.25
  done
done
for k in UP UP UP UP UP UP UP UP UP UP UP UP UP UP UP UP UP UP UP UP; do A input keyevent KEYCODE_DPAD_$k; sleep 0.15; done
G=$(A dumpsys gfxinfo $PKG)
FRAMES=$(echo "$G" | awk -F': ' '/Total frames rendered/ {print $2; exit}')
JANK=$(echo "$G" | awk -F': ' '/Janky frames:/ {print $2; exit}')
P50=$(echo "$G" | awk -F': ' '/50th percentile/ {print $2; exit}')
P90=$(echo "$G" | awk -F': ' '/90th percentile/ {print $2; exit}')
P99=$(echo "$G" | awk -F': ' '/99th percentile/ {print $2; exit}')
ALIVE=$([ -n "$(A pidof $PKG)" ] && echo yes || echo NO)
CRASH=$(adb logcat -d -b crash 2>/dev/null | grep -c "FATAL EXCEPTION" || true)

# 4) System-wide kill-all baseline, NOT the launcher's selective broom cleaner.
# The launcher protects recent apps and active media; this command does not exercise that logic.
# Open the three companion apps, return Home, then ask Android to kill background processes.
APPS="com.aly.sahlquran/.QuranActivity com.aly.sahlpdf/.LibraryActivity com.aly.sahlfiles/.FilesActivity"
for a in $APPS; do A am start -W -n "$a" >/dev/null; sleep 3; A input keyevent KEYCODE_HOME; sleep 1; done
WARM_APPS=(); for a in $APPS; do WARM_APPS+=("$(start_ms "$a")"); sleep 2; A input keyevent KEYCODE_HOME; sleep 1; done
sleep 3
M_BEFORE=$(avail_mb)
A am kill-all                                   # system-wide baseline only
sleep 2; M_AFTER=$(avail_mb)
sleep 60; M_1MIN=$(avail_mb)
AFTER_APPS=(); for a in $APPS; do AFTER_APPS+=("$(start_ms "$a")"); sleep 2; A input keyevent KEYCODE_HOME; sleep 1; done
FREED=$((M_AFTER - M_BEFORE)); KEPT="-"
[ "$FREED" -gt 0 ] && KEPT=$(( (M_1MIN - M_BEFORE) * 100 / FREED ))
WA=$(printf '%s\n' "${WARM_APPS[@]}" | grep -E '^[0-9]+$' | median); AA=$(printf '%s\n' "${AFTER_APPS[@]}" | grep -E '^[0-9]+$' | median)

{
B=${RAM#*-}; [ "$B" = "$RAM" ] || [ "$B" = "0" ] && B=latest
echo "## Performance · build ${B} · emulator with ${RAM%%-*} MB RAM (TV total ${TOTAL_RAM} MB, low-RAM flag: ${LOWRAM:-no})"
echo
echo "| Measure | Result |"
echo "|---|---|"
echo "| Cold start (process starts, until first frame), median of 5 | **${COLD_MED} ms** (runs: ${COLD[*]}) |"
echo "| Apps on screen after a cold start, median of 5 | **${READY_MED} ms** (runs: ${READY[*]}) |"
echo "| Return to Home (warm), median of 5 | **${WARM_MED} ms** (runs: ${WARM[*]}) |"
echo "| Memory used by Sahl TV (PSS) | **${PSS:-?} MB** |"
echo "| Remote navigation: 80 key presses | ${FRAMES:-?} frames · janky ${JANK:-?} · p50 ${P50:-?} · p90 ${P90:-?} · p99 ${P99:-?} |"
echo "| Still running after navigation / crashes | ${ALIVE} / ${CRASH} |"
echo "| Free memory: before → after system kill-all → 1 min later | ${M_BEFORE} → ${M_AFTER} → ${M_1MIN} MB |"
echo "| Freed, and still free after 1 min | **${FREED} MB**, **${KEPT}%** kept |"
echo "| Sahl apps open time: before system kill-all → after (median) | ${WA} ms → **${AA} ms** |"
echo
echo "> Memory results use Android's system-wide am kill-all baseline, not Sahl TV's selective cleaner. They do not measure the broom button's benefit."
if [ "$KEPT" != "-" ] && [ "$KEPT" -lt 25 ]; then echo "> System kill-all gives little lasting memory benefit here: less than 25% of the initial gain remains after a minute. See the app-open timings for restart cost."; fi
if [ "$ALIVE" != "yes" ] || [ "$CRASH" != "0" ]; then echo "> ⚠️ Sahl TV stopped or crashed during the test — see the logcat artifact."; fi
if [ "$CRASH" != "0" ]; then
  echo; echo "<details><summary>Crash details</summary>"; echo; echo '```'
  adb logcat -d -b crash 2>/dev/null | tr -d '\r' | grep -E "FATAL EXCEPTION|Process:|Exception|Error|at com\.aly" | head -30
  echo '```'; echo "</details>"
fi
} > "$OUT"
adb logcat -d > "logcat-$RAM.txt" 2>/dev/null || true
cat "$OUT"
# a crash fails the test
[ "$ALIVE" = "yes" ] && [ "$CRASH" = "0" ]
