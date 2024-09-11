package papa.test

import android.os.Build.VERSION
import android.view.MotionEvent
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import papa.Choreographers
import papa.MainThreadMessageSpy
import papa.MainThreadMessageSpy.Tracer
import papa.MainThreadTriggerStack
import papa.internal.mainHandler
import papa.internal.postAtFrontOfQueueAsync
import papa.mainThreadMessageScopedLazy
import papa.test.utilities.SkipTestIf
import papa.test.utilities.TestActivity
import papa.test.utilities.dismissCheckForUpdates
import papa.test.utilities.getOnMainSync
import papa.test.utilities.location
import papa.test.utilities.runOnMainSync
import papa.test.utilities.sendTap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.time.Duration

class MainThreadMessageSpyTest {

  @get:Rule
  val skipTestRule = SkipTestIf {
    VERSION.SDK_INT == 28
  }

  @Test fun current_message_set_to_runnable_to_string() {
    val runnableRan = CountDownLatch(1)
    var runnableCurrentMessageAsString: String? = null

    class MyRunnable : Runnable {
      override fun run() {
        runnableCurrentMessageAsString = MainThreadMessageSpy.currentMessageAsString
        runnableRan.countDown()
      }

      override fun toString(): String {
        return "Baguette"
      }
    }

    mainHandler.post(MyRunnable())
    check(runnableRan.await(5, SECONDS))

    assertThat(runnableCurrentMessageAsString).doesNotContain("MyRunnable")
    assertThat(runnableCurrentMessageAsString).contains("Baguette")
  }

  @Test fun onCurrentMessageFinished_runs_at_end_of_current_post() {
    val runnablesRan = CountDownLatch(3)
    val runOrder = mutableListOf<String>()

    mainHandler.post {
      mainHandler.postAtFrontOfQueueAsync {
        runOrder += "second post"
        runnablesRan.countDown()
      }
      MainThreadMessageSpy.onCurrentMessageFinished {
        runOrder += "first post finished"
        runnablesRan.countDown()
      }
      runOrder += "first post"
      runnablesRan.countDown()
    }
    check(runnablesRan.await(5, SECONDS))

    assertThat(runOrder)
      .containsExactly("first post", "first post finished", "second post")
      .inOrder()
  }

  @Test
  fun no_ConcurrentModificationException_when_iterating_after_onCurrentMessageFinished_removes_its_tracer() {
    runOnMainSync {
      MainThreadMessageSpy.onCurrentMessageFinished {}
      MainThreadMessageSpy.onCurrentMessageFinished {}
    }
  }

  @Test
  fun enabled_by_default() {
    val enabled = getOnMainSync {
      MainThreadMessageSpy.enabled
    }

    assertThat(enabled).isTrue()
  }

  @Test
  fun stop_spying_disables() {
    runOnMainSync {
      MainThreadMessageSpy.stopSpyingMainThreadDispatching()
    }

    val enabled = getOnMainSync {
      MainThreadMessageSpy.enabled
    }

    assertThat(enabled).isFalse()

    runOnMainSync {
      MainThreadMessageSpy.startSpyingMainThreadDispatching()
    }
  }

  @Test
  fun currentMessageAsString_is_null_when_disabled() {
    runOnMainSync {
      MainThreadMessageSpy.stopSpyingMainThreadDispatching()
    }

    val mainThreadMessageAsString = getOnMainSync {
      MainThreadMessageSpy.currentMessageAsString
    }

    assertThat(mainThreadMessageAsString).isNull()

    runOnMainSync {
      MainThreadMessageSpy.startSpyingMainThreadDispatching()
    }
  }

  @Test
  fun new_tracer_gets_single_call_after_at_end_of_message_that_adds_tracer() {
    val traced = mutableListOf<Pair<String, Boolean>>()
    val tracer = Tracer { messageAsString, before ->
      traced += messageAsString to before
    }

    runOnMainSync {
      MainThreadMessageSpy.addTracer(tracer)
    }

    assertThat(traced).hasSize(1)
    val before = traced.first().second
    assertThat(before).isFalse()
    runOnMainSync {
      MainThreadMessageSpy.removeTracer(tracer)
    }
  }

  @Test
  fun remove_tracer_gets_before_call_but_not_after_call() {
    val traced = mutableListOf<Pair<String, Boolean>>()
    val tracer = Tracer { messageAsString, before ->
      traced += messageAsString to before
    }
    runOnMainSync {
      MainThreadMessageSpy.addTracer(tracer)
    }

    runOnMainSync {
      MainThreadMessageSpy.removeTracer(tracer)
    }

    assertThat(traced).hasSize(2)
    val before = traced.last().second
    assertThat(before).isTrue()
  }

  @Test
  fun inputTrigger_rendered_on_next_frame() {
    var isFrameRenderedInChoreographerFrame: Boolean? = null
    var inputTriggerFrameRenderedUptimeOnClick: Duration? = null
    var inputCallbackFrameRenderedUptime: Duration? = null
    var inputTriggerFrameRenderedUptime: Duration? = null
    ActivityScenario.launch(TestActivity::class.java).use { scenario ->
      dismissCheckForUpdates()
      val inputEventRendered = CountDownLatch(1)
      scenario.onActivity { activity ->
        activity.setContentView(Button(activity).apply {
          text = "Click Me"
          setOnTouchListener { _, motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_UP) {
              // Update UI, triggering a frame.
              text = "Got ACTION_UP"
              val tapTrigger = MainThreadTriggerStack.inputEventInteractionTriggers
                .single()
                .payload
              inputTriggerFrameRenderedUptimeOnClick = tapTrigger.renderedUptime
              tapTrigger.onInputEventFrameRendered { frameRenderedUptime ->
                isFrameRenderedInChoreographerFrame = Choreographers.isInChoreographerFrame()
                inputCallbackFrameRenderedUptime = frameRenderedUptime
                inputTriggerFrameRenderedUptime = tapTrigger.renderedUptime
                inputEventRendered.countDown()
              }
            }
            true
          }
        })
      }

      onView(withText("Click Me")).location.sendTap()

      check(inputEventRendered.await(5, SECONDS))
    }

    assertThat(inputTriggerFrameRenderedUptimeOnClick).isNull()
    assertThat(inputCallbackFrameRenderedUptime).isEqualTo(inputTriggerFrameRenderedUptime)
    assertThat(isFrameRenderedInChoreographerFrame).isTrue()
  }

  @Test
  fun mainThreadMessageScopedLazy_value_is_cached_for_same_message() {
    val scopedLazy by mainThreadMessageScopedLazy { Any() }
    val (msg1Read1, msg1Read2) = getOnMainSync {
      scopedLazy to scopedLazy
    }

    assertThat(msg1Read1).isSameInstanceAs(msg1Read2)
  }

  @Test
  fun mainThreadMessageScopedLazy_value_is_cleared_between_messages() {
    val scopedLazy by mainThreadMessageScopedLazy { Any() }
    val msg1Read = getOnMainSync {
      scopedLazy
    }
    val msg2Read = getOnMainSync {
      scopedLazy
    }

    assertThat(msg1Read).isNotSameInstanceAs(msg2Read)
  }
}
