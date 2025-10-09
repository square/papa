package papa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

@RunWith(RobolectricTestRunner::class)
class InteractionTriggerTest {

  private class FakeInteractionTrace : InteractionTrace {
    var endTraceCalled = false
      private set

    override fun endTrace() {
      endTraceCalled = true
    }
  }

  @Test
  fun `triggerNow creates SimpleInteractionTrigger with correct properties`() {
    val name = "test-trigger"
    val beforeTime = System.nanoTime()

    val trigger = InteractionTrigger.triggerNow(name)

    val afterTime = System.nanoTime()

    assertEquals(name, trigger.name)
    assertTrue(trigger.triggerUptime.inWholeNanoseconds >= beforeTime)
    assertTrue(trigger.triggerUptime.inWholeNanoseconds <= afterTime)
    assertTrue(trigger is SimpleInteractionTrigger)
  }

  @Test
  fun `SimpleInteractionTrigger constructor sets properties correctly`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"

    val trigger = SimpleInteractionTrigger(triggerUptime, name)

    assertEquals(triggerUptime, trigger.triggerUptime)
    assertEquals(name, trigger.name)
  }

  @Test
  fun `SimpleInteractionTrigger with InteractionTrace constructor sets properties correctly`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"
    val trace = FakeInteractionTrace()

    val trigger = SimpleInteractionTrigger(triggerUptime, name, trace)

    assertEquals(triggerUptime, trigger.triggerUptime)
    assertEquals(name, trigger.name)
  }

  @Test
  fun `takeOverInteractionTrace returns trace and sets it to null`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"
    val trace = FakeInteractionTrace()

    val trigger = SimpleInteractionTrigger(triggerUptime, name, trace)

    // First call should return the trace
    val returnedTrace = trigger.takeOverInteractionTrace()
    assertSame(trace, returnedTrace)

    // Second call should return null since trace was taken over
    val secondTrace = trigger.takeOverInteractionTrace()
    assertNull(secondTrace)
  }

  @Test
  fun `takeOverInteractionTrace with null trace returns null`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"

    val trigger = SimpleInteractionTrigger(triggerUptime, name, null)

    val returnedTrace = trigger.takeOverInteractionTrace()
    assertNull(returnedTrace)
  }

  @Test
  fun `SimpleInteractionTrigger equals compares name and triggerUptime`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"

    val trigger1 = SimpleInteractionTrigger(triggerUptime, name)
    val trigger2 = SimpleInteractionTrigger(triggerUptime, name)
    val trigger3 = SimpleInteractionTrigger(triggerUptime, name, FakeInteractionTrace())

    assertEquals(trigger1, trigger2)
    assertEquals(trigger2, trigger3)
  }

  @Test
  fun `multiple simple triggers with same name but different times are not equal`() {
    val name = "test-trigger"
    val time1 = 1000.nanoseconds
    val time2 = 2000.nanoseconds

    val trigger1 = SimpleInteractionTrigger(time1, name)
    val trigger2 = SimpleInteractionTrigger(time2, name)

    assertNotEquals(trigger1, trigger2)
  }

  @Test
  fun `multiple simple triggers with same time but different names are not equal`() {
    val time = 1000.nanoseconds
    val name1 = "trigger-1"
    val name2 = "trigger-2"

    val trigger1 = SimpleInteractionTrigger(time, name1)
    val trigger2 = SimpleInteractionTrigger(time, name2)

    assertNotEquals(trigger1, trigger2)
  }

  @Test
  fun `SimpleInteractionTrigger toString contains name and triggerUptime`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"

    val trigger = SimpleInteractionTrigger(triggerUptime, name)
    val toString = trigger.toString()

    assertTrue(toString.contains(name))
    assertTrue(toString.contains(triggerUptime.toString()))
    assertTrue(toString.contains("InteractionTrigger"))
  }

  @Test
  fun `InteractionTriggerWithPayload constructor sets properties correctly`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"
    val trace = FakeInteractionTrace()
    val payload = "test-payload"

    val trigger = InteractionTriggerWithPayload(triggerUptime, name, trace, payload)

    assertEquals(triggerUptime, trigger.triggerUptime)
    assertEquals(name, trigger.name)
    assertEquals(payload, trigger.payload)
  }

  @Test
  fun `InteractionTriggerWithPayload takeOverInteractionTrace works correctly`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"
    val trace = FakeInteractionTrace()
    val payload = "test-payload"

    val trigger = InteractionTriggerWithPayload(triggerUptime, name, trace, payload)

    // Should delegate to underlying SimpleInteractionTrigger
    val returnedTrace = trigger.takeOverInteractionTrace()
    assertSame(trace, returnedTrace)

    // Second call should return null
    val secondTrace = trigger.takeOverInteractionTrace()
    assertNull(secondTrace)
  }

  @Test
  fun `InteractionTriggerWithPayload equals works correctly`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"
    val payload = "test-payload"

    val trigger1 = InteractionTriggerWithPayload(triggerUptime, name, null, payload)
    val trigger2 = InteractionTriggerWithPayload(triggerUptime, name, null, payload)
    val trigger3 =
      InteractionTriggerWithPayload(triggerUptime, name, FakeInteractionTrace(), payload)
    val trigger4 = InteractionTriggerWithPayload(triggerUptime, name, null, "other-payload")

    assertEquals(trigger1, trigger2)
    assertEquals(trigger1, trigger3) // Should equal based on name and triggerUptime
    assertEquals(trigger1, trigger4) // Should equal based on name and triggerUptime
  }

  @Test
  fun `multiple payload triggers with same name but different times are not equal`() {
    val name = "test-trigger"
    val time1 = 1000.nanoseconds
    val time2 = 2000.nanoseconds
    val payload = "test-payload"

    val trigger1 = InteractionTriggerWithPayload(time1, name, null, payload)
    val trigger2 = InteractionTriggerWithPayload(time2, name, null, payload)

    assertNotEquals(trigger1, trigger2)
  }

  @Test
  fun `multiple paylod triggers with same time but different names are not equal`() {
    val time = 1000.nanoseconds
    val name1 = "trigger-1"
    val name2 = "trigger-2"
    val payload = "test-payload"

    val trigger1 = InteractionTriggerWithPayload(time, name1, null, payload)
    val trigger2 = InteractionTriggerWithPayload(time, name2, null, payload)

    assertNotEquals(trigger1, trigger2)
  }

  @Test
  fun `InteractionTriggerWithPayload toString contains payload`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"
    val payload = "test-payload"

    val trigger = InteractionTriggerWithPayload(triggerUptime, name, null, payload)
    val toString = trigger.toString()

    assertTrue(toString.contains(name))
    assertTrue(toString.contains(triggerUptime.toString()))
    assertTrue(toString.contains(payload))
    assertTrue(toString.contains("InteractionTrigger"))
  }

  @Test
  fun `InteractionTriggerWithPayload with complex payload`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"

    data class TestPayload(
      val id: Int,
      val description: String
    )

    val payload = TestPayload(42, "test description")

    val trigger = InteractionTriggerWithPayload(triggerUptime, name, null, payload)

    assertEquals(payload, trigger.payload)
    assertEquals(42, trigger.payload.id)
    assertEquals("test description", trigger.payload.description)
  }

  @Test
  fun `different trigger types with same name and time are not equal`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"

    val simpleTrigger = SimpleInteractionTrigger(triggerUptime, name)
    val payloadTrigger = InteractionTriggerWithPayload(triggerUptime, name, null, "payload")

    assertNotEquals(simpleTrigger, payloadTrigger)
  }

  @Test
  fun `InteractionTrace is created and handled correctly in triggerNow`() {
    val name = "test-trigger"

    val trigger = InteractionTrigger.triggerNow(name) as SimpleInteractionTrigger
    val trace = trigger.takeOverInteractionTrace()

    assertNotNull(trace)
    // Trace should be null after being taken over
    assertNull(trigger.takeOverInteractionTrace())
  }

  @Test
  fun `InteractionTrigger duration handling`() {
    val millisDuration = 500.milliseconds
    val nanosDuration = millisDuration.inWholeNanoseconds.nanoseconds
    val name = "duration-test"

    val trigger = SimpleInteractionTrigger(millisDuration, name)

    assertEquals(millisDuration, trigger.triggerUptime)
    assertEquals(nanosDuration, trigger.triggerUptime)
  }

  @Test
  fun `InteractionTrace endTrace can be called`() {
    val trace = FakeInteractionTrace()
    assertFalse(trace.endTraceCalled)

    trace.endTrace()
    assertTrue(trace.endTraceCalled)
  }
}
