package tart.internal

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.FrameMetrics.INTENDED_VSYNC_TIMESTAMP
import android.view.Window
import android.view.Window.OnFrameMetricsAvailableListener
import androidx.annotation.RequiresApi
import kotlin.LazyThreadSafetyMode.NONE

/**
 * Copied from https://github.com/square/curtains
 *
 * There's a bug in S where you might be called with the previous frame.
 *
 * Work around: use System.nanoTime() as your 'now' and as long as frame finish
 * is after that 'now' then you're looking at the right frame (thx @jrek for the tip)
 *
 * This impl is also different in that it always eventually calls back, and it's up to the consumer
 * to handle potentially missed metrics. Otherwise it's a weird API if you don't always get called
 * back.
 *
 * See https://cs.android.com/android/_/android/platform/frameworks/base/+/92b71e564fd6eab47ffb7f050163d80a9b3d3afe:libs/hwui/jni/android_graphics_HardwareRendererObserver.cpp;l=69-85;drc=master
 */
@RequiresApi(26)
internal class CurrentFrameMetricsListener(
  private val callback: (FrameMetrics) -> Unit
) : OnFrameMetricsAvailableListener {

  private val listenerCreationNanos = System.nanoTime()

  @Volatile
  private var removed = false

  override fun onFrameMetricsAvailable(
    window: Window,
    frameMetrics: FrameMetrics,
    dropCountSinceLastInvocation: Int
  ) {
    if (removed) {
      return
    }

    val drawEndNanos =
      frameMetrics.getMetric(INTENDED_VSYNC_TIMESTAMP) +
        frameMetrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) +
        frameMetrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
        frameMetrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
        frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
        frameMetrics.getMetric(FrameMetrics.DRAW_DURATION)

    // Bug in Android S, we can be called with data from a previous frame. So if drawing for that
    // frame finished before we set the listener, we definitely need to wait for another frame.
    if (drawEndNanos < listenerCreationNanos) {
      return
    }

    if (!removed) {
      removed = true
      // We're on the frame metrics threads, the listener is stored in a non thread
      // safe list so we need to jump back to the main thread to remove.
      mainThreadHandler.post {
        window.removeOnFrameMetricsAvailableListener(this)
      }
    }

    callback(frameMetrics)
  }

  companion object {
    private val mainThreadHandler by lazy(NONE) {
      Handler(Looper.getMainLooper())
    }
  }
}

private val frameMetricsHandler by lazy {
  val thread = HandlerThread("frame_metrics_tart")
  thread.start()
  Handler(thread.looper)
}

@RequiresApi(26)
internal fun Window.onNextFrameMetrics(onNextFrameMetrics: (FrameMetrics) -> Unit) {
  val frameMetricsListener = CurrentFrameMetricsListener(onNextFrameMetrics)
  addOnFrameMetricsAvailableListener(frameMetricsListener, frameMetricsHandler)
}