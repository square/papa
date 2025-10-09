package papa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

  @Test
  fun `currentTriggers returns empty list initially`() {
    val triggers = MainThreadTriggerStack.currentTriggers
    assertTrue(triggers.isEmpty())
  }

  @Test
  fun `earliestInteractionTrigger returns null when stack is empty`() {
    val earliest = MainThreadTriggerStack.earliestInteractionTrigger
    assertNull(earliest)
  }

  @Test
  fun `inputEventInteractionTriggers returns empty list when stack is empty`() {
    val triggers = MainThreadTriggerStack.inputEventInteractionTriggers
    assertTrue(triggers.isEmpty())
  }

  @Test
  fun `triggeredBy adds trigger to stack during block execution`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = false) {
      val currentTriggers = MainThreadTriggerStack.currentTriggers
      assertEquals(1, currentTriggers.size)
      assertEquals(trigger, currentTriggers[0])
    }
  }

  @Test
  fun `triggeredBy removes trigger from stack after block execution`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = false) {
      // Trigger should be present during execution
      assertFalse(MainThreadTriggerStack.currentTriggers.isEmpty())
    }

    // Trigger should be removed after execution
    assertTrue(MainThreadTriggerStack.currentTriggers.isEmpty())
  }

  @Test
  fun `triggeredBy returns value from block`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")
    val expectedResult = "test-result"

    val result = MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = false) {
      expectedResult
    }

    assertEquals(expectedResult, result)
  }

  @Test
  fun `triggeredBy with endTraceAfterBlock true calls endTrace on trigger`() {
    val trace = FakeInteractionTrace()
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger", trace)

    assertFalse(trace.endTraceCalled)

    MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = true) {
      // Trace should not be ended yet during block execution
      assertFalse(trace.endTraceCalled)
    }

    // Trace should be ended after block execution
    assertTrue(trace.endTraceCalled)
  }

  @Test
  fun `triggeredBy with endTraceAfterBlock false does not call endTrace`() {
    val trace = FakeInteractionTrace()
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger", trace)

    MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = false) {
      // Do nothing
    }

    // Trace should not be ended
    assertFalse(trace.endTraceCalled)
  }

  @Test
  fun `triggeredBy removes existing trigger with same properties`() {
    val trigger1 = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")
    val trigger2 = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger") // Same properties

    MainThreadTriggerStack.triggeredBy(trigger1, endTraceAfterBlock = false) {
      // First trigger should be in stack
      assertEquals(1, MainThreadTriggerStack.currentTriggers.size)
      assertEquals(trigger1, MainThreadTriggerStack.currentTriggers[0])

      MainThreadTriggerStack.triggeredBy(trigger2, endTraceAfterBlock = false) {
        // Second trigger should replace first (only one trigger in stack)
        val triggers = MainThreadTriggerStack.currentTriggers
        assertEquals(1, triggers.size)
        assertEquals(trigger2, triggers[0])
      }
    }
  }

  @Test
  fun `triggeredBy allows different triggers`() {
    val trigger1 = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger-1")
    val trigger2 =
      SimpleInteractionTrigger(2000.nanoseconds, "test-trigger-2") // Different properties

    MainThreadTriggerStack.triggeredBy(trigger1, endTraceAfterBlock = false) {
      // Should not throw since triggers are different
      MainThreadTriggerStack.triggeredBy(trigger2, endTraceAfterBlock = false) {
        val triggers = MainThreadTriggerStack.currentTriggers
        assertEquals(2, triggers.size)
        assertTrue(triggers.contains(trigger1))
        assertTrue(triggers.contains(trigger2))
      }
    }
  }

  @Test
  fun `triggeredBy removes trigger even when exception is thrown`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    try {
      MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = false) {
        // Verify trigger is in stack
        assertEquals(1, MainThreadTriggerStack.currentTriggers.size)
        throw RuntimeException("Test exception")
      }
    } catch (_: RuntimeException) {
      // Expected exception
    }

    // Trigger should still be removed from stack despite exception
    assertTrue(MainThreadTriggerStack.currentTriggers.isEmpty())
  }

  @Test
  fun `triggeredBy calls endTrace even when exception is thrown`() {
    val trace = FakeInteractionTrace()
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger", trace)

    try {
      MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = true) {
        throw RuntimeException("Test exception")
      }
    } catch (_: RuntimeException) {
      // Expected exception
    }

    // Trace should still be ended despite exception
    assertTrue(trace.endTraceCalled)
  }

  @Test
  fun `earliestInteractionTrigger returns trigger with earliest uptime`() {
    val trigger1 = SimpleInteractionTrigger(3000.nanoseconds, "trigger-1")
    val trigger2 = SimpleInteractionTrigger(1000.nanoseconds, "trigger-2") // Earliest
    val trigger3 = SimpleInteractionTrigger(2000.nanoseconds, "trigger-3")

    MainThreadTriggerStack.triggeredBy(trigger1, endTraceAfterBlock = false) {
      MainThreadTriggerStack.triggeredBy(trigger2, endTraceAfterBlock = false) {
        MainThreadTriggerStack.triggeredBy(trigger3, endTraceAfterBlock = false) {
          val earliest = MainThreadTriggerStack.earliestInteractionTrigger
          assertEquals(trigger2, earliest)
        }
      }
    }
  }

  @Test
  fun `pushTriggeredBy adds trigger to stack`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    assertTrue(MainThreadTriggerStack.currentTriggers.isEmpty())

    MainThreadTriggerStack.pushTriggeredBy(trigger)

    val triggers = MainThreadTriggerStack.currentTriggers
    assertEquals(1, triggers.size)
    assertEquals(trigger, triggers[0])

    // Clean up
    MainThreadTriggerStack.popTriggeredBy(trigger)
  }

  @Test
  fun `pushTriggeredBy throws when same trigger instance already exists`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    MainThreadTriggerStack.pushTriggeredBy(trigger)

    assertThrows(IllegalStateException::class.java) {
      MainThreadTriggerStack.pushTriggeredBy(trigger) // Same instance
    }

    // Clean up
    MainThreadTriggerStack.popTriggeredBy(trigger)
  }

  @Test
  fun `pushTriggeredBy allows different trigger instances with same properties`() {
    val trigger1 = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")
    val trigger2 = SimpleInteractionTrigger(
      1000.nanoseconds,
      "test-trigger"
    ) // Same properties, different instance

    MainThreadTriggerStack.pushTriggeredBy(trigger1)

    // Should not throw since it's a different instance
    MainThreadTriggerStack.pushTriggeredBy(trigger2)

    val triggers = MainThreadTriggerStack.currentTriggers
    assertEquals(2, triggers.size)

    // Clean up
    MainThreadTriggerStack.popTriggeredBy(trigger1)
    MainThreadTriggerStack.popTriggeredBy(trigger2)
  }

  @Test
  fun `popTriggeredBy removes trigger from stack`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    MainThreadTriggerStack.pushTriggeredBy(trigger)
    assertEquals(1, MainThreadTriggerStack.currentTriggers.size)

    MainThreadTriggerStack.popTriggeredBy(trigger)
    assertTrue(MainThreadTriggerStack.currentTriggers.isEmpty())
  }

  @Test
  fun `popTriggeredBy handles non-existent trigger gracefully`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")
    val otherTrigger = SimpleInteractionTrigger(2000.nanoseconds, "other-trigger")

    MainThreadTriggerStack.pushTriggeredBy(trigger)
    assertEquals(1, MainThreadTriggerStack.currentTriggers.size)

    // Popping a different trigger should not affect the stack
    MainThreadTriggerStack.popTriggeredBy(otherTrigger)
    assertEquals(1, MainThreadTriggerStack.currentTriggers.size)

    // Clean up
    MainThreadTriggerStack.popTriggeredBy(trigger)
    assertTrue(MainThreadTriggerStack.currentTriggers.isEmpty())
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
        val inputTriggers = MainThreadTriggerStack.inputEventInteractionTriggers
        // Both triggers should be filtered out since neither has InputEventTrigger payload
        assertTrue(inputTriggers.isEmpty())
      }
    }
  }

  @Test
  fun `currentTriggers returns copy of stack`() {
    val trigger = SimpleInteractionTrigger(1000.nanoseconds, "test-trigger")

    MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = false) {
      val triggers1 = MainThreadTriggerStack.currentTriggers
      val triggers2 = MainThreadTriggerStack.currentTriggers

      // Should return different instances (copies)
      assertNotSame(triggers1, triggers2)

      // But with same content
      assertEquals(triggers1, triggers2)
    }
  }

  @Test
  fun `nested triggeredBy calls work correctly`() {
    val outerTrigger = SimpleInteractionTrigger(1000.nanoseconds, "outer")
    val innerTrigger = SimpleInteractionTrigger(2000.nanoseconds, "inner")

    MainThreadTriggerStack.triggeredBy(outerTrigger, endTraceAfterBlock = false) {
      assertEquals(1, MainThreadTriggerStack.currentTriggers.size)
      assertEquals(outerTrigger, MainThreadTriggerStack.earliestInteractionTrigger)

      MainThreadTriggerStack.triggeredBy(
        innerTrigger,
        endTraceAfterBlock = false
      ) {
        assertEquals(2, MainThreadTriggerStack.currentTriggers.size)
        assertEquals(
          outerTrigger,
          MainThreadTriggerStack.earliestInteractionTrigger
        ) // Still earliest

        val triggers = MainThreadTriggerStack.currentTriggers
        assertTrue(triggers.contains(outerTrigger))
        assertTrue(triggers.contains(innerTrigger))
      }

      // Inner trigger should be removed
      assertEquals(1, MainThreadTriggerStack.currentTriggers.size)
      assertEquals(outerTrigger, MainThreadTriggerStack.currentTriggers[0])
    }

    // All triggers should be removed
    assertTrue(MainThreadTriggerStack.currentTriggers.isEmpty())
  }
}
