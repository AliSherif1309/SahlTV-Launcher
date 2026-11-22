package com.aly.sahltimer

/** Deadline uses elapsed realtime: clock/time-zone changes never change the countdown. */
internal class TimerState(var duration: Long = 5 * 60_000L, var left: Long = duration,
                          var deadline: Long = 0L) {
    val running get() = deadline > 0L
    fun remaining(now: Long) = if (running) (deadline - now).coerceAtLeast(0L) else left
    fun start(now: Long) { if (!running && left > 0) deadline = now + left }
    fun pause(now: Long) { left = remaining(now); deadline = 0 }
    fun reset() { deadline = 0; left = duration }
    fun select(minutes: Int) { duration = minutes.coerceIn(1, 180) * 60_000L; reset() }
    fun finish(now: Long): Boolean {
        if (!running || remaining(now) > 0) return false
        left = 0; deadline = 0; return true
    }
}

internal fun timerText(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) + 999) / 1000
    return java.lang.String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}

/** Elapsed-realtime stopwatch: clock/time-zone changes never affect elapsed time. */
internal class StopwatchState(var accumulated: Long = 0L, var startedAt: Long = 0L) {
    val running get() = startedAt > 0L
    fun elapsed(now: Long) = if (running) accumulated + (now - startedAt).coerceAtLeast(0L) else accumulated
    fun start(now: Long) { if (!running) startedAt = now }
    fun pause(now: Long) { accumulated = elapsed(now); startedAt = 0L }
    fun reset() { accumulated = 0L; startedAt = 0L }
}

internal fun stopwatchText(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) java.lang.String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else java.lang.String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
}

