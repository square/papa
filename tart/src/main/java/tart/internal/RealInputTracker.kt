package tart.internal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import com.squareup.tart.R
import curtains.Curtains
import curtains.KeyEventInterceptor
import curtains.OnRootViewAddedListener
import curtains.TouchEventInterceptor
import curtains.keyEventInterceptors
import curtains.phoneWindow
import curtains.touchEventInterceptors
import curtains.windowAttachCount
import logcat.logcat
import tart.DeliveredInput
import tart.InputTracker
import tart.OkTrace
import tart.legacy.FrozenFrameOnTouchDetector.findPressedView
import tart.okTrace

internal object RealInputTracker : InputTracker {

  override val motionEventTriggeringClick: DeliveredInput<MotionEvent>?
    get() = motionEventTriggeringClickLocal.get()

  override val currentKeyEvent: DeliveredInput<KeyEvent>?
    get() = currentKeyEventLocal.get()

  private val motionEventTriggeringClickLocal = ThreadLocal<DeliveredInput<MotionEvent>>()
  private val currentKeyEventLocal = ThreadLocal<DeliveredInput<KeyEvent>>()

  private val handler = Handler(Looper.getMainLooper())

  private val listener = OnRootViewAddedListener { view ->
    view.phoneWindow?.let { window ->
      if (view.windowAttachCount == 0) {
        window.touchEventInterceptors += TouchEventInterceptor { motionEvent, dispatch ->
          val isActionUp = motionEvent.action == MotionEvent.ACTION_UP
          // Note: what if we get 2 taps in a single dispatch loop? Then we're simply posting the
          // following: (recordTouch, onClick, clearTouch, recordTouch, onClick, clearTouch).
          val deliveryUptimeMillis = SystemClock.uptimeMillis()

          val (actionUpEvent, cookie) = if (isActionUp) {
            val input = DeliveredInput(MotionEvent.obtain(motionEvent), deliveryUptimeMillis)
            val cookie = deliveryUptimeMillis.rem(Int.MAX_VALUE).toInt()
            input to cookie
          } else {
            null to 0
          }

          val setEventForPostedClick = Runnable {
            OkTrace.endAsyncSection(ON_CLICK_QUEUED_NAME, cookie)
            motionEventTriggeringClickLocal.set(actionUpEvent)
          }

          if (actionUpEvent != null) {
            handler.post(setEventForPostedClick)
          }

          val dispatchState = okTrace({ MotionEvent.actionToString(motionEvent.action) }) {
            // Storing in case the action up is immediately triggering a click.
            motionEventTriggeringClickLocal.set(actionUpEvent)
            try {
              dispatch(motionEvent)
            } finally {
              motionEventTriggeringClickLocal.set(null)
            }
          }

          // Android posts onClick callbacks when it receives the up event. So here we leverage
          // afterTouchEvent at which point the onClick has been posted, and by posting then we ensure
          // we're clearing the event right after the onclick is handled.
          if (isActionUp) {
            OkTrace.beginAsyncSection(ON_CLICK_QUEUED_NAME, cookie)
            val clearEventForPostedClick = Runnable {
              actionUpEvent!!.event.recycle()
              if (motionEventTriggeringClickLocal.get() === actionUpEvent) {
                motionEventTriggeringClickLocal.set(null)
              }
            }

            val dispatchEnd = SystemClock.uptimeMillis()
            val pressedView = (window.decorView as? ViewGroup)?.findPressedView()
            // AbsListView subclasses post clicks with a delay.
            // https://issuetracker.google.com/issues/232962097
            if (pressedView is AbsListView) {
              val listViewTapDelay = ViewConfiguration.getPressedStateDuration()
              val setEventTime = (deliveryUptimeMillis + listViewTapDelay) - 1
              val clearEventTime = dispatchEnd + listViewTapDelay
              handler.removeCallbacks(setEventForPostedClick)
              handler.postAtTime(setEventForPostedClick, setEventTime)
              handler.postAtTime(clearEventForPostedClick, clearEventTime)
            } else {
              handler.post(clearEventForPostedClick)
            }
          }
          dispatchState
        }
        window.keyEventInterceptors += KeyEventInterceptor { keyEvent, dispatch ->
          val now = SystemClock.uptimeMillis()
          val input = DeliveredInput(keyEvent, now)

          okTrace({ keyEvent.name }) {
            currentKeyEventLocal.set(input)
            try {
              dispatch(keyEvent)
            } finally {
              currentKeyEventLocal.set(null)
            }
          }
        }
      }
    }
  }

  internal fun install() {
    val context = ApplicationHolder.application
    if (context == null) {
      logcat { "Application not set, not tracking input events" }
      return
    }
    if (context.resources.getBoolean(R.bool.tart_track_input_events)) {
      Curtains.onRootViewsChangedListeners += listener
    }
  }

  private const val ON_CLICK_QUEUED_NAME = "View OnClick queued"

  val KeyEvent.name: String
    get() = "${keyActionToString()} ${KeyEvent.keyCodeToString(keyCode)}"

  private fun KeyEvent.keyActionToString(): String {
    @Suppress("DEPRECATION")
    return when (action) {
      KeyEvent.ACTION_DOWN -> "ACTION_DOWN"
      KeyEvent.ACTION_UP -> "ACTION_UP"
      KeyEvent.ACTION_MULTIPLE -> "ACTION_MULTIPLE"
      else -> action.toString()
    }
  }
}
