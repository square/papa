package papa.test.utilities

import android.graphics.Point
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.hamcrest.Matcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference

fun Point.sendTap(
  downTime: Long = SystemClock.uptimeMillis()
) {
  val instrumentation = InstrumentationRegistry.getInstrumentation()
  instrumentation.sendPointerSync(
    MotionEvent.obtain(
      downTime,
      downTime,
      MotionEvent.ACTION_DOWN,
      x.toFloat(),
      y.toFloat(),
      0
    )
  )
  instrumentation.sendPointerSync(
    MotionEvent.obtain(
      downTime,
      downTime + 50,
      MotionEvent.ACTION_UP,
      x.toFloat(),
      y.toFloat(),
      0
    )
  )
}

val ViewInteraction.location: Point
  get() {
    val waitForView = CountDownLatch(1)
    val viewCenterPoint = AtomicReference<Point>()
    perform(object : ViewAction {
      override fun getDescription() = "Finding view"

      override fun getConstraints(): Matcher<View> = ViewMatchers.isDisplayed()

      override fun perform(
        uiController: UiController,
        view: View
      ) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val centerX = location[0] + view.width / 2
        val centerY = location[1] + view.height / 2
        viewCenterPoint.set(Point(centerX, centerY))
        waitForView.countDown()
      }
    })
    check(waitForView.await(10, SECONDS))
    return viewCenterPoint.get()!!
  }

fun dismissCheckForUpdates() {
  val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
  val deprecationDialog = uiDevice.wait(
    Until.findObject(
      By.pkg("android").depth(0)
    ),
    500
  )
  if (deprecationDialog != null) {
    val okButton = deprecationDialog.findObject(By.text("OK"))!!
    okButton.click()
  }
}
