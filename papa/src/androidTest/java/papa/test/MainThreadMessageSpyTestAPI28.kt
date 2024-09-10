package papa.test

import android.os.Build.VERSION
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import papa.MainThreadMessageSpy
import papa.test.utilities.SkipTestIf
import papa.test.utilities.getOnMainSync
import papa.test.utilities.runOnMainSync

class MainThreadMessageSpyTestAPI28 {

  @get:Rule
  val skipTestRule = SkipTestIf {
    VERSION.SDK_INT != 28
  }

  @Test
  fun cannot_enable_on_API_28() {
    val enabled = getOnMainSync {
      MainThreadMessageSpy.startSpyingMainThreadDispatching()
      MainThreadMessageSpy.enabled
    }

    assertThat(enabled).isFalse()
  }

  @Test
  fun currentMessageAsString_is_null_on_API_28() {
    runOnMainSync {
      MainThreadMessageSpy.startSpyingMainThreadDispatching()
    }
    val mainThreadMessageAsString = getOnMainSync {
      MainThreadMessageSpy.startSpyingMainThreadDispatching()
      MainThreadMessageSpy.currentMessageAsString
    }

    assertThat(mainThreadMessageAsString).isNull()
  }
}