package tart

import android.content.Context
import android.os.Build
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import android.view.WindowManager
import tart.internal.ApplicationHolder
import tart.internal.enforceMainThread
import tart.internal.isOnMainThread
import tart.internal.lastFrameTimeNanos
import tart.internal.mainHandler
import tart.internal.onNextFrameMetrics
import tart.internal.postAtFrontOfQueueAsync
import java.util.concurrent.TimeUnit.NANOSECONDS
import kotlin.LazyThreadSafetyMode.NONE

// TODO Not sure if we should expose this. postFrameCallback() isn't tied to any
// particular window, so this window might not actually render if there's no reason to re render.
// If that happens the next frame metrics will be much later.
// We probably need to break up "frozen touch" and "frozen frame" into 2 different things.
internal fun Window.onNextFrameDisplayed(callback: (CpuDuration) -> Unit) {
  if (isChoreographerDoingFrame()) {
    val frameTimeNanos = Choreographer.getInstance().lastFrameTimeNanos
    onCurrentFrameDisplayed(frameTimeNanos, callback)
  } else {
    Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
      onCurrentFrameDisplayed(frameTimeNanos, callback)
    }
  }
}

// TODO Should this be on an object instead?
internal fun isChoreographerDoingFrame(): Boolean {
  if (!isOnMainThread()) {
    return false
  }
  val stackTrace = RuntimeException().stackTrace
  for (i in stackTrace.lastIndex downTo 0) {
    val element = stackTrace[i]
    if (element.className == "android.view.Choreographer" &&
      element.methodName == "doFrame"
    ) {
      return true
    }
  }
  return false
}

internal val expectedFrameDurationNanos by lazy(NONE) {
  val windowManager =
    ApplicationHolder.application?.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
  var refreshRate = 60f
  if (windowManager != null) {
    @Suppress("DEPRECATION")
    val display = windowManager.defaultDisplay
    refreshRate = display.refreshRate
  }
  if (refreshRate < 30f || refreshRate > 200f) {
    // Account for faulty return values (including 0)
    refreshRate = 60f
  }
  val msPerSecond = 1000
  val nanosPerMs = 1_000_000
  (msPerSecond / refreshRate * nanosPerMs).toLong()
}

internal fun Window.onCurrentFrameDisplayed(
  frameTimeNanos: Long,
  callback: (CpuDuration) -> Unit,
) {
  enforceMainThread()
  if (Build.VERSION.SDK_INT >= 26) {
    var frameEndSent = false
    mainHandler.postAtFrontOfQueueAsync {
      val frameEnd = CpuDuration.now()
      mainHandler.postDelayed({
        if (!frameEndSent) {
          frameEndSent = true
          callback(frameEnd)
        }
      }, 100)
    }
    onNextFrameMetrics { frameMetrics ->
      val metricsFrameTimeNanos = frameMetrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
      if (metricsFrameTimeNanos == frameTimeNanos) {
        val intendedVsync = frameMetrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        // TOTAL_DURATION is the duration from the intended vsync
        // time, not the actual vsync time.
        val frameDuration = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
        // Note: this is the time at which the window content was handed over to the display
        // subsystem, but it's not the time at which that frame was visible to the user.
        // The frame becomes visible on the next vsync.
        // Several windows rendered in the same choreographer callback will have the same
        // INTENDED_VSYNC_TIMESTAMP but different TOTAL_DURATION as they're rendered serially.
        val bufferSwapUptimeNanos = intendedVsync + frameDuration
        val bufferSwap = CpuDuration.fromUptime(NANOSECONDS, bufferSwapUptimeNanos)
        mainHandler.post {
          if (!frameEndSent) {
            frameEndSent = true
            callback(bufferSwap)
          }
        }
      }
    }
  } else {
    mainHandler.postAtFrontOfQueueAsync {
      callback(CpuDuration.now())
    }
  }
}
