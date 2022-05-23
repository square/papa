package tart.test

import android.app.AlertDialog
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.squareup.tart.test.R
import curtains.TouchEventInterceptor
import curtains.touchEventInterceptors
import org.hamcrest.Matcher
import org.junit.Test
import radiography.Radiography
import radiography.ScannableView.AndroidView
import radiography.ViewStateRenderer
import radiography.ViewStateRenderers.DefaultsIncludingPii
import tart.AndroidComponentEvent
import tart.AppStart.AppStartData
import tart.TartEvent.FrozenFrameOnTouch
import tart.TartEventListener
import tart.internal.Perfs
import tart.internal.isChoreographerDoingFrame
import tart.internal.mainHandler
import tart.test.utilities.TestActivity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference

class PerfMonitoringTest {

  @Test fun initialAppStart() {
    assertThat(appStart.processStartUptimeMillis)
      .isLessThan(SystemClock.uptimeMillis())
    assertThat(appStart.firstAppClassLoadElapsedUptimeMillis).isGreaterThan(0)
    assertThat(appStart.firstComponentInstantiated).isNull()
  }

  @Test fun firstActivity() {
    ActivityScenario.launch(TestActivity::class.java).use {
      // Our activity isn't actually first when testing, oh well.
      val firstActivity = "androidx.test.core.app.InstrumentationActivityInvoker\$BootstrapActivity"
      assertThat(appStart.firstActivityOnCreate!!.name).isEqualTo(
        firstActivity
      )

      val firstActivityOnCreate =
        appStart.firstActivityOnCreate?.let {
          AndroidComponentEvent(
            it.name,
            it.elapsedUptimeMillis
          )
        }

      var previousElapsed = appStart.firstAppClassLoadElapsedUptimeMillis
      for (
      (activityEvent, lifecycle) in listOf(
        firstActivityOnCreate to "onCreate()",
        appStart.firstActivityOnStart to "onStart()",
        appStart.firstActivityOnResume to "onResume()",
        // Unfortunately the bootstrap activity doesn't get laid out / drawn
        // appStart.firstGlobalLayout to "onGlobalLayout()",
        // appStart.firstPreDraw to "onPreDraw()",
        // appStart.firstDraw to "onDraw()"
      )
      ) {
        assertWithMessage("For $lifecycle").that(activityEvent)
          .isNotNull()
        assertWithMessage("For $lifecycle").that(activityEvent!!.name)
          .isEqualTo(firstActivity)
        assertWithMessage("For $lifecycle").that(activityEvent.elapsedUptimeMillis)
          .isAtLeast(previousElapsed)
        previousElapsed = activityEvent.elapsedUptimeMillis
      }
    }
  }

  @Test fun customEvents() {
    runOnMainSync {
      Perfs.customFirstEvent(eventName = "Kouign-amann")
      SystemClock.sleep(500)
      Perfs.customFirstEvent(eventName = "Croissant", extra = "Pain au chocolat")
      Perfs.customFirstEvent(eventName = "Croissant", extra = "Chocolatine")
    }

    assertThat(appStart.customFirstEvents["Kouign-amann"]?.first).isLessThan(
      appStart.customFirstEvents["Croissant"]?.first
    )
    assertThat(appStart.customFirstEvents["Croissant"]?.second).isEqualTo("Pain au chocolat")
  }

  // Note: this test adds a lot of debugging info which helped figure out flakes.
  @Test fun frozenFrames() {
    val waitForFrozenFrame = reportFrozenFrame()

    val onTouchEventViewHierarchies = CopyOnWriteArrayList<String>()

    ActivityScenario.launch(TestActivity::class.java).use { scenario ->

      scenario.onActivity { activity ->
        val dialog = AlertDialog.Builder(activity)
          // The standard dialog buttons are in a scrollview.
          // Scrollable containers delay the pressed state, making the test flaky.
          .setView(Button(activity).apply {
            id = R.id.dialog_view
            // The tap is late (late, latte) and it leads to frozen frames (frozen, frappé coffee)
            text = "Better latte than frappé"
          })
          .show()

        dialog.window!!.touchEventInterceptors += TouchEventInterceptor { motionEvent, dispatch ->
          dispatch(motionEvent).apply {
            onTouchEventViewHierarchies += "############\n" +
              "Touch event was $motionEvent\n" +
              Radiography.scan(viewStateRenderers = DefaultsIncludingPii + ViewStateRenderer { view ->
                if (view is AndroidView) {
                  append("pressed:${view.view.isPressed}")
                }
              })
          }
        }
      }

      val (buttonX, buttonY) = findDialogButtonCoordinates(R.id.dialog_view)

      sendTapAtTime(SystemClock.uptimeMillis() - 2000, buttonX, buttonY)

      val frozenFrameOnTouch = waitForFrozenFrame()

      assertThat(frozenFrameOnTouch.deliverDurationUptimeMillis).isAtLeast(2000)
      assertWithMessage(
        "Result: $frozenFrameOnTouch\n" +
          "Touch Events:\n$onTouchEventViewHierarchies"
      ).that(frozenFrameOnTouch.pressedView)
        .contains("id/dialog_view")
    }
  }

  @Test fun Choreographer_is_doing_Frame() {
    val frameCallbackLatch = CountDownLatch(1)
    var isChoreographerDoingFrame = false
    mainHandler.post {
      Choreographer.getInstance().postFrameCallback {
        isChoreographerDoingFrame = isChoreographerDoingFrame()
        frameCallbackLatch.countDown()
      }
    }
    check(frameCallbackLatch.await(10, SECONDS))
    assertThat(isChoreographerDoingFrame).isTrue()
  }

  @Test fun Choreographer_not_doing_Frame() {
    val frameCallbackLatch = CountDownLatch(1)
    var isChoreographerDoingFrame = false
    mainHandler.post {
      isChoreographerDoingFrame = isChoreographerDoingFrame()
      frameCallbackLatch.countDown()
    }
    check(frameCallbackLatch.await(10, SECONDS))
    assertThat(isChoreographerDoingFrame).isFalse()
  }

  private fun reportFrozenFrame(): () -> FrozenFrameOnTouch {
    val waitForFrozenFrame = CountDownLatch(1)
    val frozenFrameOnTouchRef = AtomicReference<FrozenFrameOnTouch>()
    val registration = getOnMainSync {
      TartEventListener.install { tartEvent ->
        if (tartEvent is FrozenFrameOnTouch) {
          frozenFrameOnTouchRef.set(tartEvent)
          waitForFrozenFrame.countDown()
        }
      }
    }
    return {
      check(waitForFrozenFrame.await(10, SECONDS))
      registration.close()
      frozenFrameOnTouchRef.get()!!
    }
  }

  private fun runOnMainSync(block: () -> Unit) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
  }

  private fun <T> getOnMainSync(block: () -> T): T {
    val resultHolder = AtomicReference<T>()
    val latch = CountDownLatch(1)
    InstrumentationRegistry.getInstrumentation()
      .runOnMainSync {
        resultHolder.set(block())
        latch.countDown()
      }
    latch.await()
    return resultHolder.get()
  }

  private fun findDialogButtonCoordinates(buttonResId: Int): Pair<Int, Int> {
    val waitForDialogButton = CountDownLatch(1)
    val dialogButtonCenter = AtomicReference<Pair<Int, Int>>()
    onView(withId(buttonResId)).inRoot(isDialog())
      .perform(object : ViewAction {
        override fun getDescription() = "Retrieving dialog ok button"

        override fun getConstraints(): Matcher<View> = ViewMatchers.isDisplayed()

        override fun perform(
          uiController: UiController,
          view: View
        ) {
          val location = IntArray(2)
          view.getLocationOnScreen(location)
          val centerX = location[0] + view.width / 2
          val centerY = location[1] + view.height / 2
          dialogButtonCenter.set(centerX to centerY)
          waitForDialogButton.countDown()
        }
      })
    check(waitForDialogButton.await(10, SECONDS))
    return dialogButtonCenter.get()!!
  }

  private fun sendTapAtTime(
    downTime: Long,
    clickX: Int,
    clickY: Int
  ) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.sendPointerSync(
      MotionEvent.obtain(
        downTime,
        downTime,
        MotionEvent.ACTION_DOWN,
        clickX.toFloat(),
        clickY.toFloat(),
        0
      )
    )
    instrumentation.sendPointerSync(
      MotionEvent.obtain(
        downTime,
        downTime + 50,
        MotionEvent.ACTION_UP,
        clickX.toFloat(),
        clickY.toFloat(),
        0
      )
    )
  }

  private val appStart: AppStartData
    get() = Perfs.appStart as AppStartData
}