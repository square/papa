package tart.internal

import android.os.Build
import android.os.Handler
import android.os.Message

// Thx @chet and @jreck
// https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:metrics/metrics-performance/src/main/java/androidx/metrics/performance/JankStatsApi16Impl.kt;l=66;drc=523d7a11e46390281ed3f77893671730cd6edb98
internal fun Handler.postAtFrontOfQueueAsync(callback: () -> Unit) {
  sendMessageAtFrontOfQueue(Message.obtain(this, callback).apply {
    if (Build.VERSION.SDK_INT >= 22) {
      isAsynchronous = true
    }
  })
}