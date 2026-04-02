package papa

import android.view.MotionEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.nanoseconds

@RunWith(RobolectricTestRunner::class)
class MainThreadTriggerStackTest {

  private class FakeInteractionTrace : InteractionTrace {
    var endTraceCalled = false
      private set

    override fun endTrace() {
      endTraceCalled = true
    }
  }

  private fun createInputEventPayload(): InputEventTrigger {
    val motionEvent = MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_UP, 0f, 0f, 0)
    return InputEventTrigger.createForTest(motionEvent, 1000.nanoseconds)
  }

  @Test
  fun `currentTriggers returns empty list initially`() {
    val triggers = MainThreadTriggerStack.currentTriggers
    assertThat(triggers).isEmpty()
  }

  @Test
  fun `earliestInteractionTrigger returns null when stack is empty`() {
    val earliest = MainThreadTriggerStack.earliestInteractionTrigger
    assertThat(earliest).isNull()
  }

  @Test
  fun `inputEventInteractionTriggers returns empty list when stack is empty`() {
    val triggers = MainThreadTriggerStack.inputEventInteractionTriggers
    assertThat(triggers).isEmpty()
  }

  @Test
  fun `triggeredBy adds trigger to stack during block execution`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = false) {
      val currentTriggers = MainThreadTriggerStack.currentTriggers
      assertThat(currentTriggers).containsExactly(trigger)
    }
  }

  @Test
  fun `triggeredBy removes trigger from stack after block execution`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = false) {
      assertThat(MainThreadTriggerStack.currentTriggers).isNotEmpty()
    }

    assertThat(MainThreadTriggerStack.currentTriggers).isEmpty()
  }

  @Test
  fun `triggeredBy returns value from block`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")
    val expectedResult = "test-result"

    val result = MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = false) {
      expectedResult
    }

    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `triggeredBy with endTraceAfterBlock true calls endTrace on trigger`() {
    val trace = FakeInteractionTrace()
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger", trace)

    assertThat(trace.endTraceCalled).isFalse()

    MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = true) {
      assertThat(trace.endTraceCalled).isFalse()
    }

    assertThat(trace.endTraceCalled).isTrue()
  }

  @Test
  fun `triggeredBy with endTraceAfterBlock false does not call endTrace`() {
    val trace = FakeInteractionTrace()
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger", trace)

    MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = false) {
      // Do nothing
    }

    assertThat(trace.endTraceCalled).isFalse()
  }

  @Test
  fun `earliestInteractionTrigger prefers most recent duplicate trigger with same uptime`() {
    val originalTrigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")
    val duplicateTrigger =
      SimpleInteractionTrigger(1000.nanoseconds, "test-trigger") // Same uptime

    MainThreadTriggerStack.pushTriggeredBy(originalTrigger)
    try {
      MainThreadTriggerStack.triggeredBy(duplicateTrigger, endTraceAfterBlock = false) {
        val triggers = MainThreadTriggerStack.currentTriggers
        assertThat(triggers).containsExactly(originalTrigger, duplicateTrigger).inOrder()
        assertThat(MainThreadTriggerStack.earliestInteractionTrigger).isSameInstanceAs(
          duplicateTrigger
        )
      }

      assertThat(MainThreadTriggerStack.earliestInteractionTrigger).isSameInstanceAs(
        originalTrigger
      )

      val triggersAfterBlock = MainThreadTriggerStack.currentTriggers
      assertThat(triggersAfterBlock).containsExactly(originalTrigger)
    } finally {
      MainThreadTriggerStack.popTriggeredBy(originalTrigger)
    }
  }

  @Test
  fun `triggeredBy leaves original trigger on stack after duplicate exits`() {
    val originalTrigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")
    val duplicateTrigger =
      SimpleInteractionTrigger(1000.nanoseconds, "test-trigger") // Same uptime

    MainThreadTriggerStack.pushTriggeredBy(originalTrigger)
    try {
      MainThreadTriggerStack.triggeredBy(duplicateTrigger, endTraceAfterBlock = false) {
        assertThat(MainThreadTriggerStack.earliestInteractionTrigger).isSameInstanceAs(
          duplicateTrigger
        )
      }

      val triggersAfterBlock = MainThreadTriggerStack.currentTriggers
      assertThat(triggersAfterBlock).containsExactly(originalTrigger)
      assertThat(MainThreadTriggerStack.earliestInteractionTrigger).isSameInstanceAs(
        originalTrigger
      )
    } finally {
      MainThreadTriggerStack.popTriggeredBy(originalTrigger)
    }
  }

  @Test
  fun `triggeredBy allows different triggers`() {
    val trigger1 = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger-1")
    val trigger2 =
      SimpleInteractionTrigger(2000.nanoseconds, "test-trigger-2") // Different properties

    MainThreadTriggerStack.triggeredBy(trigger1, endTraceAfterBlock = false) {
      MainThreadTriggerStack.triggeredBy(trigger2, endTraceAfterBlock = false) {
        val triggers = MainThreadTriggerStack.currentTriggers
        assertThat(triggers).containsExactly(trigger1, trigger2).inOrder()
      }
    }
  }

  @Test
  fun `triggeredBy removes trigger even when exception is thrown`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    assertThrows(RuntimeException::class.java) {
      MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = false) {
        assertThat(MainThreadTriggerStack.currentTriggers).containsExactly(trigger)
        throw RuntimeException("Test exception")
      }
    }

    assertThat(MainThreadTriggerStack.currentTriggers).isEmpty()
  }

  @Test
  fun `triggeredBy calls endTrace even when exception is thrown`() {
    val trace = FakeInteractionTrace()
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger", trace)

    assertThrows(RuntimeException::class.java) {
      MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = true) {
        throw RuntimeException("Test exception")
      }
    }

    assertThat(trace.endTraceCalled).isTrue()
  }

  @Test
  fun `earliestInteractionTrigger returns trigger with earliest uptime`() {
    val trigger1 = SimpleInteractionTrigger(3000.nanoseconds, "trigger-1")
    val trigger2 = SimpleInteractionTrigger(1000.nanoseconds, "trigger-2") // Earliest
    val trigger3 = SimpleInteractionTrigger(2000.nanoseconds, "trigger-3")

    MainThreadTriggerStack.triggeredBy(trigger1, endTraceAfterBlock = false) {
      MainThreadTriggerStack.triggeredBy(trigger2, endTraceAfterBlock = false) {
        MainThreadTriggerStack.triggeredBy(trigger3, endTraceAfterBlock = false) {
          assertThat(MainThreadTriggerStack.earliestInteractionTrigger).isSameInstanceAs(trigger2)
        }
      }
    }
  }

  @Test
  fun `pushTriggeredBy adds trigger to stack`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    assertThat(MainThreadTriggerStack.currentTriggers).isEmpty()

    MainThreadTriggerStack.pushTriggeredBy(trigger)

    val triggers = MainThreadTriggerStack.currentTriggers
    assertThat(triggers).containsExactly(trigger)

    MainThreadTriggerStack.popTriggeredBy(trigger)
  }

  @Test
  fun `pushTriggeredBy throws when same trigger instance already exists`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    MainThreadTriggerStack.pushTriggeredBy(trigger)

    assertThrows(IllegalStateException::class.java) {
      MainThreadTriggerStack.pushTriggeredBy(trigger) // Same instance
    }

    MainThreadTriggerStack.popTriggeredBy(trigger)
  }

  @Test
  fun `pushTriggeredBy allows different trigger instances with same uptime`() {
    val trigger1 = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")
    val trigger2 = SimpleInteractionTrigger(
      1000.nanoseconds,
      "test-trigger"
    ) // Same uptime, different instance

    MainThreadTriggerStack.pushTriggeredBy(trigger1)

    MainThreadTriggerStack.pushTriggeredBy(trigger2)

    val triggers = MainThreadTriggerStack.currentTriggers
    assertThat(triggers).containsExactly(trigger1, trigger2).inOrder()

    MainThreadTriggerStack.popTriggeredBy(trigger1)
    MainThreadTriggerStack.popTriggeredBy(trigger2)
  }

  @Test
  fun `popTriggeredBy removes trigger from stack`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    MainThreadTriggerStack.pushTriggeredBy(trigger)
    assertThat(MainThreadTriggerStack.currentTriggers).containsExactly(trigger)

    MainThreadTriggerStack.popTriggeredBy(trigger)
    assertThat(MainThreadTriggerStack.currentTriggers).isEmpty()
  }

  @Test
  fun `popTriggeredBy handles non-existent trigger gracefully`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")
    val otherTrigger = SimpleInteractionTrigger(2000.nanoseconds, "other-trigger")

    MainThreadTriggerStack.pushTriggeredBy(trigger)
    assertThat(MainThreadTriggerStack.currentTriggers).containsExactly(trigger)

    MainThreadTriggerStack.popTriggeredBy(otherTrigger)
    assertThat(MainThreadTriggerStack.currentTriggers).containsExactly(trigger)

    MainThreadTriggerStack.popTriggeredBy(trigger)
    assertThat(MainThreadTriggerStack.currentTriggers).isEmpty()
  }

  @Test
  fun `inputEventInteractionTriggers returns only InputEventTrigger payloads`() {
    val regularTrigger = SimpleInteractionTrigger(1000.nanoseconds, "regular")
    val inputEventTrigger = InteractionTriggerWithPayload(
      2000.nanoseconds,
      "input-event",
      null,
      "not-an-input-event" // This is not an InputEventTrigger, so should be filtered out
    )

    MainThreadTriggerStack.triggeredBy(
      regularTrigger,
      endTraceAfterBlock = false
    ) {
      MainThreadTriggerStack.triggeredBy(
        inputEventTrigger,
        endTraceAfterBlock = false
      ) {
        assertThat(MainThreadTriggerStack.inputEventInteractionTriggers).isEmpty()
      }
    }
  }

  @Test
  fun `inputEventInteractionTriggers keeps duplicate triggers in stack order`() {
    val payload = createInputEventPayload()
    val originalTrigger = InteractionTriggerWithPayload(1000.nanoseconds, "tap", null, payload)
    val duplicateTrigger = InteractionTriggerWithPayload(1000.nanoseconds, "tap", null, payload)

    MainThreadTriggerStack.pushTriggeredBy(originalTrigger)
    try {
      MainThreadTriggerStack.triggeredBy(duplicateTrigger, endTraceAfterBlock = false) {
        val inputTriggers = MainThreadTriggerStack.inputEventInteractionTriggers
        assertThat(inputTriggers).containsExactly(originalTrigger, duplicateTrigger).inOrder()
      }

      val inputTriggersAfterBlock = MainThreadTriggerStack.inputEventInteractionTriggers
      assertThat(inputTriggersAfterBlock).containsExactly(originalTrigger)
    } finally {
      MainThreadTriggerStack.popTriggeredBy(originalTrigger)
    }
  }

  @Test
  fun `currentTriggers returns copy of stack`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = false) {
      val triggers1 = MainThreadTriggerStack.currentTriggers
      val triggers2 = MainThreadTriggerStack.currentTriggers

      assertThat(triggers1).isNotSameInstanceAs(triggers2)
      assertThat(triggers1).containsExactlyElementsIn(triggers2).inOrder()
    }
  }

  @Test
  fun `nested triggeredBy calls work correctly`() {
    val outerTrigger = SimpleInteractionTrigger(1000.nanoseconds, "outer")
    val innerTrigger = SimpleInteractionTrigger(2000.nanoseconds, "inner")

    MainThreadTriggerStack.triggeredBy(outerTrigger, endTraceAfterBlock = false) {
      assertThat(MainThreadTriggerStack.currentTriggers).containsExactly(outerTrigger)
      assertThat(MainThreadTriggerStack.earliestInteractionTrigger).isSameInstanceAs(outerTrigger)

      MainThreadTriggerStack.triggeredBy(
        innerTrigger,
        endTraceAfterBlock = false
      ) {
        assertThat(MainThreadTriggerStack.currentTriggers).containsExactly(
          outerTrigger,
          innerTrigger
        ).inOrder()
        assertThat(
          MainThreadTriggerStack.earliestInteractionTrigger
        ).isSameInstanceAs(outerTrigger)
      }

      assertThat(MainThreadTriggerStack.currentTriggers).containsExactly(outerTrigger)
    }

    assertThat(MainThreadTriggerStack.currentTriggers).isEmpty()
  }
}
