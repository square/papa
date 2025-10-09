package papa.test.utilities

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference

fun <T> getOnMainSync(block: () -> T): T {
  val resultHolder = AtomicReference<T>()
  runOnMainSync {
    resultHolder.set(block())
  }
  return resultHolder.get()
}

fun runOnMainSync(block: Runnable) {
  InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
