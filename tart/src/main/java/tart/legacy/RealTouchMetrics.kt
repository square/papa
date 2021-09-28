package tart.legacy

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import curtains.Curtains
import curtains.OnRootViewAddedListener
import curtains.TouchEventInterceptor
import curtains.phoneWindow
import curtains.touchEventInterceptors

class RealTouchMetrics : TouchMetrics {

  override var lastTouchUpEvent: Pair<MotionEvent, Long>? = null

  private val handler = Handler(Looper.getMainLooper())

  private val clearLastTouchUpEvent = Runnable {
    lastTouchUpEvent = null
  }

  private val listener = OnRootViewAddedListener { view ->
    view.phoneWindow?.let { window ->
      window.touchEventInterceptors += TouchEventInterceptor { motionEvent, dispatch ->
        val isActionUp = motionEvent.action == MotionEvent.ACTION_UP
        if (isActionUp) {
          lastTouchUpEvent?.first?.recycle()
          lastTouchUpEvent = MotionEvent.obtain(motionEvent) to SystemClock.uptimeMillis()
          handler.removeCallbacks(clearLastTouchUpEvent)
        }
        val dispatchState = dispatch(motionEvent)
        // Android posts onClick callbacks when it receives the up event. So here we leverage
        // afterTouchEvent at which point the onClick has been posted, and by posting then we ensure
        // we're clearing the event right after the onclick is handled.
        if (isActionUp) {
          handler.post(clearLastTouchUpEvent)
        }
        dispatchState
      }
    }
  }

  fun install() {
    Curtains.onRootViewsChangedListeners += listener
  }

  fun uninstall() {
    Curtains.onRootViewsChangedListeners -= listener
  }
}
