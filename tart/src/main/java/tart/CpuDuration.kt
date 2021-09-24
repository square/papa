package tart

import android.os.SystemClock

class CpuDuration(
  val uptimeMillis: Long,
  val realtimeMillis: Long
) {
  operator fun minus(start: CpuDuration) =
    CpuDuration(uptimeMillis - start.uptimeMillis, realtimeMillis - start.realtimeMillis)

  val realtimeDriftMillis: Long
    get() = realtimeMillis - uptimeMillis

  override fun toString(): String {
    return "CpuDuration(uptimeMillis=$uptimeMillis, realtimeMillis=$realtimeMillis)"
  }

  companion object {
    fun now() = CpuDuration(SystemClock.uptimeMillis(), SystemClock.elapsedRealtime())
  }
}