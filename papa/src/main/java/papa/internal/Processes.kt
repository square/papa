package papa.internal

import android.system.Os
import android.system.OsConstants
import java.io.BufferedReader
import java.io.FileReader

internal object Processes {

  fun readProcessStartRealtimeMillis(pid: Int): Long {
    val ticksAtProcessStart = readProcessStartTicks(pid)
    // Min SDK 21
    val ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK)
    return ticksAtProcessStart * 1000 / ticksPerSecond
  }

  // Based on https://stackoverflow.com/a/42195623/703646
  // Benchmarked (with Jetpack Benchmark) on Pixel 3 running Android 10
  // Median time: 0.13ms
  fun readProcessStartTicks(pid: Int): Long {
    val path = "/proc/$pid/stat"
    val stat = BufferedReader(FileReader(path)).use { reader ->
      reader.readLine()
    }
    // See "man proc", under "/proc/[pid]/stat":
    // (2) comm  %s
    //          The filename of the executable, in parentheses.
    //          This is visible whether or not the executable is
    //          swapped out.
    // ...
    // (22) starttime  %llu
    //     The time the process started after system boot.  In
    //     kernels before Linux 2.6, this value was expressed
    //     in jiffies.  Since Linux 2.6, the value is expressed
    //     in clock ticks (divide by sysconf(_SC_CLK_TCK)).
    //
    // We want to read starttime, the 22nd entry (1 indexed), so we move past the parenthesis of
    // entry "2" to avoid any space in the process name, then split the remaining string on spaces
    // and pick the 20th entry at index 19.
    val fields = stat.substringAfter(") ")
      .split(' ')
    return fields[19].toLong()
  }
}