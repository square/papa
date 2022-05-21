package tart

import android.os.SystemClock
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.NANOSECONDS

// TODO Probably need to lose the CPU, SystemClock.elapsedRealtimeNanos() moves forward
// even when the CPU isn't running.
/**
 * Represents a duration in CPU time, where [uptime] provides the CPU time excluding deep sleep and
 * [realtime] provides the CPU time including deep sleep.
 */
class CpuDuration constructor(
  val unit: TimeUnit,
  private val uptimeLong: Long,
  private val realtimeLong: Long
) {

  fun uptime(targetUnit: TimeUnit): Long {
    return targetUnit.convert(uptimeLong, unit)
  }

  fun realtime(targetUnit: TimeUnit): Long {
    return targetUnit.convert(realtimeLong, unit)
  }

  operator fun minus(start: CpuDuration): CpuDuration {
    return when {
      start.unit === unit -> {
        CpuDuration(unit, uptimeLong - start.uptimeLong, realtimeLong - start.realtimeLong)
      }
      unit.ordinal < start.unit.ordinal -> {
        val startUptime = unit.convert(start.uptimeLong, start.unit)
        val startRealtime = unit.convert(start.realtimeLong, start.unit)
        CpuDuration(unit, uptimeLong - startUptime, realtimeLong - startRealtime)
      }
      else -> {
        val endUptime = start.unit.convert(uptimeLong, unit)
        val endRealtime = start.unit.convert(realtimeLong, unit)
        CpuDuration(start.unit, endUptime - start.uptimeLong, endRealtime - start.realtimeLong)
      }
    }
  }

  operator fun plus(start: CpuDuration): CpuDuration {
    return when {
      start.unit === unit -> {
        CpuDuration(unit, uptimeLong + start.uptimeLong, realtimeLong + start.realtimeLong)
      }
      unit.ordinal < start.unit.ordinal -> {
        val startUptime = unit.convert(start.uptimeLong, start.unit)
        val startRealtime = unit.convert(start.realtimeLong, start.unit)
        CpuDuration(unit, uptimeLong + startUptime, realtimeLong + startRealtime)
      }
      else -> {
        val endUptime = start.unit.convert(uptimeLong, unit)
        val endRealtime = start.unit.convert(realtimeLong, unit)
        CpuDuration(start.unit, endUptime + start.uptimeLong, endRealtime + start.realtimeLong)
      }
    }
  }

  override fun toString(): String {
    return "CpuDuration(unit=$unit, uptime=$uptimeLong, realtime=$realtimeLong)"
  }

  companion object {
    fun now() = CpuDuration(NANOSECONDS, System.nanoTime(), SystemClock.elapsedRealtimeNanos())

    fun fromUptime(unit: TimeUnit, uptime: Long, realtimeDrift: Long = unit.convert(SystemClock.elapsedRealtimeNanos() - System.nanoTime(), NANOSECONDS)): CpuDuration {
      return CpuDuration(unit, uptime, uptime + realtimeDrift)
    }

    fun fromRealtime(unit: TimeUnit, realtime: Long, realtimeDrift: Long = unit.convert(SystemClock.elapsedRealtimeNanos() - System.nanoTime(), NANOSECONDS)): CpuDuration {
      return CpuDuration(unit, realtime - realtimeDrift, realtime)
    }
  }
}