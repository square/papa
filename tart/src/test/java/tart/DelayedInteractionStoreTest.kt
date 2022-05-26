package tart

import com.google.common.truth.Truth.assertThat
import tart.Interaction.Delayed
import tart.Interaction.Delayed.End
import tart.Interaction.Delayed.End.Cancel
import org.junit.Test

class DelayedInteractionStoreTest {
  @Test
  fun `canceling kicks in when we reach buffer size`() {
    class MyInteraction1 : Interaction
    class MyInteraction2 : Interaction
    class MyInteraction3 : Interaction

    val bufferSize = 2
    val store = DelayedInteractionStore({}, bufferSize)
    var canceled = 0
    val endListener: (End) -> Unit = { end ->
      check(end is Cancel)
      canceled++
    }

    store += Delayed(MyInteraction1()).apply { endListeners += endListener }
    store += Delayed(MyInteraction2()).apply { endListeners += endListener }
    store += Delayed(MyInteraction3()).apply { endListeners += endListener }

    assertThat(canceled).isEqualTo(1)
    assertThat(store.get<MyInteraction1>().interaction).isNull() // oldest interaction is gone
    assertThat(store.get<MyInteraction2>().interaction).isNotNull()
    assertThat(store.get<MyInteraction3>().interaction).isNotNull()
  }

  @Test
  fun `canceling kicks in when adding new interaction of same class`() {
    class MyInteraction : Interaction

    val store = DelayedInteractionStore({}, 100)

    var interaction1Canceled = false
    val delayed1 = Delayed(MyInteraction()).apply {
      endListeners += {
        interaction1Canceled = true
      }
    }

    var interaction2Canceled = false
    val delayed2 = Delayed(MyInteraction()).apply {
      endListeners += {
        interaction2Canceled = true
      }
    }

    store += delayed1
    store += delayed2

    assertThat(interaction1Canceled).isTrue()
    assertThat(interaction2Canceled).isFalse()
    assertThat(store.get<MyInteraction>()).isEqualTo(delayed2)
  }
}
