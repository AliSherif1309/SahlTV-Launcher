#!/bin/bash
# Performance gate on an Android TV emulator (validate.yml); perf.yml compares historical builds.
# Measures instead of assuming:
#   1. start-up time of Sahl TV (cold, and return to Home), 5 runs each
#   2. memory used by Sahl TV
#   3. remote navigation: 60 D-pad presses across the home screen -> frames drawn, slow frames, crashes
#   4. system-wide am kill-all baseline: memory freed, how much is still free 1 minute later,
#      and how much slower the apps open afterwards (they must cold-start again)
# Usage: perf.sh <folder with the APKs> <label: RAM in MB, optionally -build>
set -u
D=$1; RAM=$2; OUT=perf-$RAM.md
PKG=com.aly.sahltv; ACT=$PKG/.MainActivity
PERF_PID=$BASHPID
PHASE=setup
# Bound every ADB call, including those in pipelines/command substitutions. Signal the
# main shell on timeout so a stalled command cannot become an empty, passing metric.
trap 'echo "ADB timed out during $PHASE" >&2; exit 124' USR1
trap 'echo "ADB failed during $PHASE" >&2; exit 1' USR2
adb() {
  local rc=0
  timeout --kill-after=5s 90s adb "$@" || rc=$?
  if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then kill -USR1 "$PERF_PID"; fi
  return "$rc"
}
finish() {
  local rc=$?
  trap - EXIT
  if [ "$rc" -ne 0 ] && [ ! -s "$OUT" ]; then
    printf '## Performance test incomplete\n\nPhase: %s; exit code: %s\n' "$PHASE" "$rc" > "$OUT"
  fi
  timeout --kill-after=2s 10s adb logcat -d >> "logcat-$RAM.txt" 2>&1 || true
  # Preserve system crashes as diagnostics, even though they do not fail Sahl.
  printf '\n--- Crash buffer captured after navigation ---\n%s\n' "${CRASH_LOG:-not captured}" >> "logcat-$RAM.txt"
  exit "$rc"
}
trap finish EXIT
phase() { PHASE=$1; printf '[perf] %s\n' "$PHASE"; }
A() {
  local result rc=0
  result=$(adb shell "$@" 2>&1) || rc=$?
  # pidof returning 1 means the launcher died; ALIVE handles that explicitly.
  if [ "$rc" -ne 0 ] && [ "$1" != pidof ]; then
    printf 'ADB shell failed: %s\n%s\n' "$*" "$result" >> "logcat-$RAM.txt"
    kill -USR2 "$PERF_PID"
    return "$rc"
  fi
  printf '%s\n' "$result" | tr -d '\r'
}
median() { sort -n | awk '{a[NR]=$1} END {if (NR==0) print "-"; else print a[int((NR+1)/2)]}'; }
start_ms() { A am start -W -n "$1" | awk -F': ' '/TotalTime/ {print $2}'; }
avail_mb() { A cat /proc/meminfo | awk '/MemAvailable/ {print int($2/1024)}'; }

phase 'boot and install'
adb wait-for-device
for i in $(seq 1 60); do [ "$(A getprop sys.boot_completed)" = "1" ] && break; sleep 2; done
[ "$(A getprop sys.boot_completed)" = "1" ] || { echo 'Emulator did not finish booting' >&2; exit 1; }
for f in SahlTV SahlQuran SahlPDF SahlFiles; do adb install -r -g "$D/$f.apk" >/dev/null 2>&1 || { echo "install $f failed" >&2; exit 1; }; done
A cmd package set-home-activity "$ACT" >/dev/null
A settings put global window_animation_scale 1; A settings put global transition_animation_scale 1
A am start -W -n "$ACT" >/dev/null; sleep 6          # first start: let it load apps, photos and caches

# 1) start-up
phase 'cold start'
COLD=(); WARM=(); READY=()
for i in 1 2 3 4 5; do
  A am force-stop $PKG; sleep 3; adb logcat -c
  COLD+=("$(start_ms $ACT)"); sleep 4
  READY+=("$(adb logcat -d -s SahlPerf:I 2>/dev/null | tr -d '\r' | sed -n 's/.*ready=\([0-9]*\).*/\1/p' | head -1)")
done
phase 'warm return to Home'
for i in 1 2 3 4 5; do
  A am start -a android.settings.SETTINGS >/dev/null; sleep 3
  A input keyevent KEYCODE_HOME; sleep 1
  WARM+=("$(start_ms $ACT)"); sleep 2
done
COLD_MED=$(printf '%s\n' "${COLD[@]}" | grep -E '^[0-9]+$' | median)
WARM_MED=$(printf '%s\n' "${WARM[@]}" | grep -E '^[0-9]+$' | median)
READY_MED=$(printf '%s\n' "${READY[@]}" | grep -E '^[0-9]+$' | median)

# 2) memory
phase memory
sleep 5
PSS=$(A dumpsys meminfo $PKG | awk '/TOTAL PSS:/ {print int($3/1024); exit} /^ *TOTAL:? / {print int($2/1024); exit}')
TOTAL_RAM=$(A cat /proc/meminfo | awk '/MemTotal/ {print int($2/1024)}')
LOWRAM=$(A getprop ro.config.low_ram)

# 3) remote navigation
phase navigation
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
CRASH_LOG=$(adb logcat -d -b crash -v brief 2>/dev/null) || { echo 'Cannot read crash buffer' >&2; exit 1; }
# Match process names, not the current PID (the launcher may have restarted).
# Keep complete matching Java crash records from the same snapshot used to count.
SAHL_CRASH_LOG=$(printf '%s\n' "$CRASH_LOG" | tr -d '\r' | awk -v pkg="$PKG" '
  function flush() { if (matches) printf "%s", block; block=""; matches=0 }
  /FATAL EXCEPTION:/ { flush(); block=$0 ORS; next }
  block != "" {
    block=block $0 ORS
    if ($0 ~ /Process: /) {
      name=$0; sub(/^.*Process: /, "", name); sub(/,.*/, "", name)
      if (name == pkg || index(name, pkg ":") == 1) matches=1
    }
  }
  END { flush() }
')
CRASH=$(printf '%s\n' "$SAHL_CRASH_LOG" | grep -c 'FATAL EXCEPTION:' || true)

# 4) System-wide kill-all baseline, NOT the launcher's selective broom cleaner.
# The launcher protects recent apps and active media; this command does not exercise that logic.
# Open the three companion apps, return Home, then ask Android to kill background processes.
APPS="com.aly.sahlquran/.QuranActivity com.aly.sahlpdf/.LibraryActivity com.aly.sahlfiles/.FilesActivity"
phase 'companion apps and system kill-all baseline'
adb logcat -d > "logcat-$RAM.txt" 2>&1 || { echo 'Cannot save pre-baseline logs' >&2; exit 1; }
for a in $APPS; do A am start -W -n "$a" >/dev/null; sleep 3; A input keyevent KEYCODE_HOME; sleep 1; done
WARM_APPS=(); for a in $APPS; do WARM_APPS+=("$(start_ms "$a")"); sleep 2; A input keyevent KEYCODE_HOME; sleep 1; done
sleep 3
M_BEFORE=$(avail_mb)
phase 'system kill-all'
A am kill-all                                   # system-wide baseline only
sleep 2; M_AFTER=$(avail_mb)
sleep 60; M_1MIN=$(avail_mb)
AFTER_APPS=(); for a in $APPS; do AFTER_APPS+=("$(start_ms "$a")"); sleep 2; A input keyevent KEYCODE_HOME; sleep 1; done
for value in "$M_BEFORE" "$M_AFTER" "$M_1MIN"; do
  [[ "$value" =~ ^[0-9]+$ ]] || { echo 'Missing memory measurement' >&2; exit 1; }
done
FREED=$((M_AFTER - M_BEFORE)); KEPT="-"
[ "$FREED" -gt 0 ] && KEPT=$(( (M_1MIN - M_BEFORE) * 100 / FREED ))
WA=$(printf '%s\n' "${WARM_APPS[@]}" | grep -E '^[0-9]+$' | median); AA=$(printf '%s\n' "${AFTER_APPS[@]}" | grep -E '^[0-9]+$' | median)
for value in "$COLD_MED" "$WARM_MED" "$READY_MED" "$PSS" "$FRAMES" "$WA" "$AA"; do
  [[ "$value" =~ ^[0-9]+$ ]] || { echo 'Missing performance measurement' >&2; exit 1; }
done

{
B=${BUILD:-${RAM#*-}}; if [ "$B" = "$RAM" ] || [ "$B" = "0" ]; then B=latest; fi
echo "## Performance · build ${B} · emulator with ${RAM%%-*} MB RAM (TV total ${TOTAL_RAM} MB, low-RAM flag: ${LOWRAM:-no})"
echo
echo "| Measure | Result |"
echo "|---|---|"
echo "| Cold start (process starts, until first frame), median of 5 | **${COLD_MED} ms** (runs: ${COLD[*]}) |"
echo "| Apps on screen after a cold start, median of 5 | **${READY_MED} ms** (runs: ${READY[*]}) |"
echo "| Return to Home (warm), median of 5 | **${WARM_MED} ms** (runs: ${WARM[*]}) |"
echo "| Memory used by Sahl TV (PSS) | **${PSS:-?} MB** |"
echo "| Remote navigation: 60 key presses | ${FRAMES:-?} frames · janky ${JANK:-?} · p50 ${P50:-?} · p90 ${P90:-?} · p99 ${P99:-?} |"
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
  printf '%s\n' "$SAHL_CRASH_LOG" | head -60
  echo '```'; echo "</details>"
fi
} > "$OUT"
cat "$OUT"
# Conservative emulator budgets, not physical-TV benchmarks. Missing metrics fail above.
# Keep timing/memory ceilings broad to tolerate shared CI hosts; reject severe regressions.
if [ "$COLD_MED" -gt 10000 ] || [ "$READY_MED" -gt 15000 ] || [ "$PSS" -gt 256 ] || [ "$FRAMES" -eq 0 ]; then
  echo '> FAIL: emulator budget exceeded (cold <=10000 ms, ready <=15000 ms, PSS <=256 MB, frames >0).' | tee -a "$OUT"
  exit 1
fi
[ "$ALIVE" = "yes" ] && [ "$CRASH" = "0" ]
