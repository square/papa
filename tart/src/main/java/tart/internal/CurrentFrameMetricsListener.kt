package tart.internal

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import android.view.Window.OnFrameMetricsAvailableListener
import androidx.annotation.RequiresApi
import kotlin.LazyThreadSafetyMode.NONE

/**
 * Copied from https://github.com/square/curtains to remove the requirement
 * to pass frameTimeNanos. We'll assume we always get called back because we're
 * always only consuming the next frame so there isn't any data waiting to be consumed.
 *
 * See https://cs.android.com/android/_/android/platform/frameworks/base/+/92b71e564fd6eab47ffb7f050163d80a9b3d3afe:libs/hwui/jni/android_graphics_HardwareRendererObserver.cpp;l=69-85;drc=master
 */
@RequiresApi(24)
internal class CurrentFrameMetricsListener(
  private val callback: (FrameMetrics) -> Unit
) : OnFrameMetricsAvailableListener {

  private var removed = false

  override fun onFrameMetricsAvailable(
    window: Window,
    frameMetrics: FrameMetrics,
    dropCountSinceLastInvocation: Int
  ) {
    // TODO We should consider having a listener forever and checking that the metrics
    // has a start before the ondraw and an end (before render thread) after the ondraw.
    // Maybe by summing up from start until DRAW_DURATION.
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

    private val frameMetricsHandler by lazy {
      val thread = HandlerThread("frame_metrics_tart")
      thread.start()
      Handler(thread.looper)
    }

    /**
     * Note: don't make this a public API, there's a bug in S where you might
     * be called with the previous frame
     * (doesn't matter for us as there's no previous frame on launch)
     *
     * One work around could be to use System.nanoTime() as your 'now' and as long as frame finish
     * is after that 'now' then you're looking at the right frame (thx @jrek for the tip)
     */
    @RequiresApi(24)
    fun Window.onNextFrameMetrics(onNextFrameMetrics: (FrameMetrics) -> Unit) {
      val frameMetricsListener = CurrentFrameMetricsListener(onNextFrameMetrics)
      addOnFrameMetricsAvailableListener(frameMetricsListener, frameMetricsHandler)
    }
  }
}