package papa

import android.view.InputEvent
import android.view.ViewTreeObserver
import android.view.Window
import papa.internal.onNextPreDraw
import java.util.WeakHashMap
import kotlin.time.Duration

class InputEventTrigger private constructor(
  val inputEvent: InputEvent,
  val deliveryUptime: Duration,
  private val frame: NextFrame
) {
  val renderedUptime: Duration?
    get() = frame.renderedUptime
  val rendered: Boolean
    get() = renderedUptime != null

  fun onInputEventFrameRendered(listener: OnFrameRenderedListener) {
    Handlers.checkOnMainThread()
    frame.onFrameRendered(listener)
  }

  /**
   * The next frame of a window, shared by all the input events it will render.
   */
  private class NextFrame {
    var renderedUptime: Duration? = null
      private set

    private var listeners = mutableListOf<OnFrameRenderedListener>()

    fun onFrameRendered(listener: OnFrameRenderedListener) {
      renderedUptime?.let {
        listener.onFrameRendered(it)
        return
      }
      listeners += listener
    }

    fun rendered(frameRenderedUptime: Duration) {
      renderedUptime = frameRenderedUptime
      val renderedListeners = listeners
      listeners = mutableListOf()
      for (listener in renderedListeners) {
        listener.onFrameRendered(frameRenderedUptime)
      }
    }
  }

  companion object {

    /**
     * The next frame of each view hierarchy, keyed by its [ViewTreeObserver].
     *
     * A window only draws when something in it changes, so it can receive input without drawing
     * for hours: a key held down on a static screen repeats 20 times a second. Sharing one
     * [NextFrame] and one pre draw listener means the window doesn't hold on to its input events.
     *
     * Keyed by [ViewTreeObserver] rather than [Window] because a window that is removed and added
     * back (e.g. a dialog shown again) gets a new one, and the old one never pre draws again.
     */
    private val nextFrames = WeakHashMap<ViewTreeObserver, NextFrame>()

    fun createTrackingWhenFrameRendered(
      inputEventWindow: Window,
      inputEvent: InputEvent,
      deliveryUptime: Duration
    ): InputEventTrigger {
      val viewTreeObserver = inputEventWindow.peekDecorView()?.viewTreeObserver
      val frame = viewTreeObserver?.let { nextFrames[it] }
        ?: inputEventWindow.trackNextFrame(viewTreeObserver)
      return InputEventTrigger(inputEvent, deliveryUptime, frame)
    }

    private fun Window.trackNextFrame(viewTreeObserver: ViewTreeObserver?): NextFrame {
      val frame = NextFrame()
      if (viewTreeObserver != null) {
        nextFrames[viewTreeObserver] = frame
      }
      onNextPreDraw {
        // Input delivered from now on is rendered by the frame after this one.
        if (viewTreeObserver != null) {
          nextFrames.remove(viewTreeObserver)
        }
        // When compiling with Java11 we get AbstractMethodError at runtime when this is a lambda.
        @Suppress("ObjectLiteralToLambda")
        Choreographers.postOnCurrentFrameRendered(object : OnFrameRenderedListener {
          override fun onFrameRendered(frameRenderedUptime: Duration) {
            frame.rendered(frameRenderedUptime)
          }
        })
      }
      return frame
    }

    /**
     * Test-only helper for callers that need an [InputEventTrigger] payload without also wiring
     * frame-render tracking through a real [Window]. This keeps unit tests focused on stack and
     * payload behavior instead of Kotlin/JVM constructor details or Android frame callbacks.
     */
    internal fun createForTest(
      inputEvent: InputEvent,
      deliveryUptime: Duration
    ): InputEventTrigger {
      return InputEventTrigger(inputEvent, deliveryUptime, NextFrame())
    }
  }
}

fun InteractionTrigger.toInputEventTriggerOrNull(): InteractionTriggerWithPayload<InputEventTrigger>? {
  @Suppress("UNCHECKED_CAST")
  return if (this is InteractionTriggerWithPayload<*> && payload is InputEventTrigger) {
    this as InteractionTriggerWithPayload<InputEventTrigger>
  } else {
    null
  }
}
