package papa

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.view.Window
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InputEventTriggerTest {

  private lateinit var activity: Activity

  private val window: Window
    get() = activity.window

  @Before
  fun setUp() {
    activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun `input events waiting for the same frame share one pre draw listener`() {
    val listenersBefore = window.decorView.viewTreeObserver.preDrawListenerCount

    repeat(100) { deliverKeyRepeat(it) }

    assertThat(window.decorView.viewTreeObserver.preDrawListenerCount)
      .isEqualTo(listenersBefore + 1)
  }

  @Test
  fun `input events waiting for the same frame are rendered by it`() {
    val triggers = List(3) { deliverKeyRepeat(it) }

    renderFrame()

    assertThat(triggers.map { it.rendered }).containsExactly(true, true, true)
    assertThat(triggers.map { it.renderedUptime }.distinct()).hasSize(1)
  }

  @Test
  fun `input event delivered after pre draw is rendered by the next frame`() {
    val beforePreDraw = deliverKeyRepeat(0)
    window.decorView.viewTreeObserver.dispatchOnPreDraw()
    val afterPreDraw = deliverKeyRepeat(1)
    shadowOf(Looper.getMainLooper()).idle()

    assertThat(beforePreDraw.rendered).isTrue()
    assertThat(afterPreDraw.rendered).isFalse()

    renderFrame()

    assertThat(afterPreDraw.rendered).isTrue()
  }

  @Test
  fun `listeners are notified when the frame rendering their input event renders`() {
    val first = deliverKeyRepeat(0)
    val second = deliverKeyRepeat(1)
    val renderedUptimes = mutableListOf<Duration>()
    first.onInputEventFrameRendered { renderedUptimes += it }
    second.onInputEventFrameRendered { renderedUptimes += it }

    renderFrame()
    first.onInputEventFrameRendered { renderedUptimes += it }

    assertThat(renderedUptimes).hasSize(3)
    assertThat(renderedUptimes.distinct()).containsExactly(first.renderedUptime)
  }

  @Test
  fun `window that does not draw does not retain its input events`() {
    deliverKeyRepeat(0)
    val trigger = WeakReference(deliverKeyRepeat(1))

    repeat(10) {
      if (trigger.get() != null) {
        System.gc()
        Thread.sleep(10)
      }
    }

    assertThat(trigger.get()).isNull()
  }

  @Test
  fun `window added back is tracked again`() {
    val decorView = window.decorView
    val layoutParams = window.attributes
    deliverKeyRepeat(0)
    activity.windowManager.removeViewImmediate(decorView)
    activity.windowManager.addView(decorView, layoutParams)
    shadowOf(Looper.getMainLooper()).idle()

    val afterAddedBack = deliverKeyRepeat(1)
    renderFrame()

    assertThat(afterAddedBack.rendered).isTrue()
  }

  private fun deliverKeyRepeat(repeatCount: Int): InputEventTrigger {
    val keyEvent = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_0, repeatCount)
    return InputEventTrigger.createTrackingWhenFrameRendered(
      inputEventWindow = window,
      inputEvent = keyEvent,
      deliveryUptime = System.nanoTime().nanoseconds
    )
  }

  private fun renderFrame() {
    window.decorView.viewTreeObserver.dispatchOnPreDraw()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private val ViewTreeObserver.preDrawListenerCount: Int
    get() {
      val listeners = ViewTreeObserver::class.java.getDeclaredField("mOnPreDrawListeners")
        .apply { isAccessible = true }
        .get(this) ?: return 0
      return listeners.javaClass.getDeclaredMethod("size")
        .apply { isAccessible = true }
        .invoke(listeners) as Int
    }
}
