package tart.legacy

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_BACK
import android.view.MotionEvent
import curtains.Curtains
import curtains.KeyEventInterceptor
import curtains.OnRootViewAddedListener
import curtains.TouchEventInterceptor
import curtains.keyEventInterceptors
import curtains.phoneWindow
import curtains.touchEventInterceptors
import curtains.windowAttachCount

class RealTouchMetrics : TouchMetrics {

  override var lastTouchUpEvent: Pair<MotionEvent, Long>? = null

  override var lastBackKeyEvent: Pair<Long, Long>? = null

  private val handler = Handler(Looper.getMainLooper())

  private val listener = OnRootViewAddedListener { view ->
    view.phoneWindow?.let { window ->
      if (view.windowAttachCount == 0) {
        window.touchEventInterceptors += TouchEventInterceptor { motionEvent, dispatch ->
          val isActionUp = motionEvent.action == MotionEvent.ACTION_UP
          // Note: what if we get 2 taps in a single dispatch loop? Then we're simply posting the
          // following: (recordTouch, onClick, clearTouch, recordTouch, onClick, clearTouch).
          if (isActionUp) {
            val touchUpCopy = MotionEvent.obtain(motionEvent) to SystemClock.uptimeMillis()
            handler.post {
              lastTouchUpEvent = touchUpCopy
            }
          }
          val dispatchState = dispatch(motionEvent)
          // Android posts onClick callbacks when it receives the up event. So here we leverage
          // afterTouchEvent at which point the onClick has been posted, and by posting then we ensure
          // we're clearing the event right after the onclick is handled.
          if (isActionUp) {
            handler.post {
              lastTouchUpEvent?.first?.recycle()
              lastTouchUpEvent = null
            }
          }
          dispatchState
        }
        window.keyEventInterceptors += KeyEventInterceptor { keyEvent, dispatch ->
          val isBackPressed = keyEvent.keyCode == KEYCODE_BACK &&
            keyEvent.action == KeyEvent.ACTION_UP &&
            !keyEvent.isCanceled

          if (isBackPressed) {
            val now = SystemClock.uptimeMillis()
            lastBackKeyEvent = keyEvent.eventTime to now
          }

          val dispatchState = dispatch(keyEvent)
          lastBackKeyEvent = null

          dispatchState
        }
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
