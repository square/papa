package papa.test

import android.app.AlertDialog
import android.os.SystemClock
import android.view.Choreographer
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.squareup.papa.test.R
import curtains.TouchEventInterceptor
import curtains.touchEventInterceptors
import org.junit.Test
import papa.AndroidComponentEvent
import papa.AppStart.AppStartData
import papa.Choreographers
import papa.MainThreadMessageSpy
import papa.PapaEvent.FrozenFrameOnTouch
import papa.PapaEventListener
import papa.internal.Perfs
import papa.internal.mainHandler
import papa.test.utilities.TestActivity
import papa.test.utilities.dismissCheckForUpdates
import papa.test.utilities.getOnMainSync
import papa.test.utilities.location
import papa.test.utilities.runOnMainSync
import papa.test.utilities.sendTap
import radiography.Radiography
import radiography.ScannableView.AndroidView
import radiography.ViewStateRenderer
import radiography.ViewStateRenderers.DefaultsIncludingPii
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
      dismissCheckForUpdates()
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
      dismissCheckForUpdates()
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
              Radiography.scan(
                viewStateRenderers = DefaultsIncludingPii + ViewStateRenderer { view ->
                  if (view is AndroidView) {
                    append("pressed:${view.view.isPressed}")
                  }
                })
          }
        }
      }

      val twoSecondsAgo = SystemClock.uptimeMillis() - 2000
      onView(withId(R.id.dialog_view)).inRoot(isDialog()).location.sendTap(twoSecondsAgo)

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
        isChoreographerDoingFrame = Choreographers.isInChoreographerFrame()
        frameCallbackLatch.countDown()
      }
    }
    check(frameCallbackLatch.await(10, SECONDS))
    assertThat(isChoreographerDoingFrame).isTrue()
  }

  @Test fun Choreographer_is_doing_Frame_no_spying() {
    postOnMainBlocking {
      MainThreadMessageSpy.stopSpyingMainThreadDispatching()
    }
    val frameCallbackLatch = CountDownLatch(1)
    var isChoreographerDoingFrame = false
    mainHandler.post {
      Choreographer.getInstance().postFrameCallback {
        isChoreographerDoingFrame = Choreographers.isInChoreographerFrame()
        frameCallbackLatch.countDown()
      }
    }
    check(frameCallbackLatch.await(10, SECONDS))
    assertThat(isChoreographerDoingFrame).isTrue()

    // reset
    postOnMainBlocking {
      MainThreadMessageSpy.startSpyingMainThreadDispatching()
    }
  }

  @Test fun Choreographer_not_doing_Frame() {
    var isChoreographerDoingFrame = false
    postOnMainBlocking {
      isChoreographerDoingFrame = Choreographers.isInChoreographerFrame()
    }
    assertThat(isChoreographerDoingFrame).isFalse()
  }

  @Test fun Choreographer_not_doing_Frame_no_spying() {
    postOnMainBlocking {
      MainThreadMessageSpy.stopSpyingMainThreadDispatching()
    }
    var isChoreographerDoingFrame = false
    postOnMainBlocking {
      isChoreographerDoingFrame = Choreographers.isInChoreographerFrame()
    }
    assertThat(isChoreographerDoingFrame).isFalse()

    // reset
    postOnMainBlocking {
      MainThreadMessageSpy.startSpyingMainThreadDispatching()
    }
  }

  private fun postOnMainBlocking(block: () -> Unit) {
    val waitForMainPost = CountDownLatch(1)
    mainHandler.post {
      block()
      waitForMainPost.countDown()
    }
    check(waitForMainPost.await(10, SECONDS))
  }

  private fun reportFrozenFrame(): () -> FrozenFrameOnTouch {
    val waitForFrozenFrame = CountDownLatch(1)
    val frozenFrameOnTouchRef = AtomicReference<FrozenFrameOnTouch>()
    val registration = getOnMainSync {
      PapaEventListener.install { tartEvent ->
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

  private val appStart: AppStartData
    get() = Perfs.appStart as AppStartData
}