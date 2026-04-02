package papa

import com.google.common.truth.Truth.assertThat
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

    assertThat(trigger.name).isEqualTo(name)
    assertThat(trigger.triggerUptime.inWholeNanoseconds).isAtLeast(beforeTime)
    assertThat(trigger.triggerUptime.inWholeNanoseconds).isAtMost(afterTime)
    assertThat(trigger).isInstanceOf(SimpleInteractionTrigger::class.java)
  }

  @Test
  fun `SimpleInteractionTrigger constructor sets properties correctly`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"

    val trigger = SimpleInteractionTrigger(triggerUptime, name)

    assertThat(trigger.triggerUptime).isEqualTo(triggerUptime)
    assertThat(trigger.name).isEqualTo(name)
  }

  @Test
  fun `SimpleInteractionTrigger with InteractionTrace constructor sets properties correctly`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"
    val trace = FakeInteractionTrace()

    val trigger = SimpleInteractionTrigger(triggerUptime, name, trace)

    assertThat(trigger.triggerUptime).isEqualTo(triggerUptime)
    assertThat(trigger.name).isEqualTo(name)
  }

  @Test
  fun `takeOverInteractionTrace returns trace and sets it to null`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"
    val trace = FakeInteractionTrace()

    val trigger = SimpleInteractionTrigger(triggerUptime, name, trace)

    val returnedTrace = trigger.takeOverInteractionTrace()
    assertThat(returnedTrace).isSameInstanceAs(trace)

    val secondTrace = trigger.takeOverInteractionTrace()
    assertThat(secondTrace).isNull()
  }

  @Test
  fun `takeOverInteractionTrace with null trace returns null`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"

    val trigger = SimpleInteractionTrigger(triggerUptime, name, null)

    val returnedTrace = trigger.takeOverInteractionTrace()
    assertThat(returnedTrace).isNull()
  }

  @Test
  fun `SimpleInteractionTrigger uses reference equality for distinct instances`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"

    val trigger1 = SimpleInteractionTrigger(triggerUptime, name)
    val trigger2 = SimpleInteractionTrigger(triggerUptime, name)
    val trigger3 = SimpleInteractionTrigger(triggerUptime, name, FakeInteractionTrace())

    assertThat(trigger1).isNotEqualTo(trigger2)
    assertThat(trigger2).isNotEqualTo(trigger3)
  }

  @Test
  fun `multiple simple triggers with same name but different times are not equal`() {
    val name = "test-trigger"
    val time1 = 1000.nanoseconds
    val time2 = 2000.nanoseconds

    val trigger1 = SimpleInteractionTrigger(time1, name)
    val trigger2 = SimpleInteractionTrigger(time2, name)

    assertThat(trigger1).isNotEqualTo(trigger2)
  }

  @Test
  fun `multiple simple triggers with same time but different names are not equal`() {
    val time = 1000.nanoseconds
    val name1 = "trigger-1"
    val name2 = "trigger-2"

    val trigger1 = SimpleInteractionTrigger(time, name1)
    val trigger2 = SimpleInteractionTrigger(time, name2)

    assertThat(trigger1).isNotEqualTo(trigger2)
  }

  @Test
  fun `SimpleInteractionTrigger toString contains name and triggerUptime`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"

    val trigger = SimpleInteractionTrigger(triggerUptime, name)
    val toString = trigger.toString()

    assertThat(toString).contains(name)
    assertThat(toString).contains(triggerUptime.toString())
    assertThat(toString).contains("InteractionTrigger")
  }

  @Test
  fun `InteractionTriggerWithPayload constructor sets properties correctly`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"
    val trace = FakeInteractionTrace()
    val payload = "test-payload"

    val trigger = InteractionTriggerWithPayload(triggerUptime, name, trace, payload)

    assertThat(trigger.triggerUptime).isEqualTo(triggerUptime)
    assertThat(trigger.name).isEqualTo(name)
    assertThat(trigger.payload).isEqualTo(payload)
  }

  @Test
  fun `InteractionTriggerWithPayload takeOverInteractionTrace works correctly`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"
    val trace = FakeInteractionTrace()
    val payload = "test-payload"

    val trigger = InteractionTriggerWithPayload(triggerUptime, name, trace, payload)

    val returnedTrace = trigger.takeOverInteractionTrace()
    assertThat(returnedTrace).isSameInstanceAs(trace)

    val secondTrace = trigger.takeOverInteractionTrace()
    assertThat(secondTrace).isNull()
  }

  @Test
  fun `InteractionTriggerWithPayload uses reference equality for distinct instances`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"
    val payload = "test-payload"

    val trigger1 = InteractionTriggerWithPayload(triggerUptime, name, null, payload)
    val trigger2 = InteractionTriggerWithPayload(triggerUptime, name, null, payload)
    val trigger3 =
      InteractionTriggerWithPayload(triggerUptime, name, FakeInteractionTrace(), payload)
    val trigger4 = InteractionTriggerWithPayload(triggerUptime, name, null, "other-payload")

    assertThat(trigger1).isNotEqualTo(trigger2)
    assertThat(trigger1).isNotEqualTo(trigger3)
    assertThat(trigger1).isNotEqualTo(trigger4)
  }

  @Test
  fun `multiple payload triggers with same name but different times are not equal`() {
    val name = "test-trigger"
    val time1 = 1000.nanoseconds
    val time2 = 2000.nanoseconds
    val payload = "test-payload"

    val trigger1 = InteractionTriggerWithPayload(time1, name, null, payload)
    val trigger2 = InteractionTriggerWithPayload(time2, name, null, payload)

    assertThat(trigger1).isNotEqualTo(trigger2)
  }

  @Test
  fun `multiple paylod triggers with same time but different names are not equal`() {
    val time = 1000.nanoseconds
    val name1 = "trigger-1"
    val name2 = "trigger-2"
    val payload = "test-payload"

    val trigger1 = InteractionTriggerWithPayload(time, name1, null, payload)
    val trigger2 = InteractionTriggerWithPayload(time, name2, null, payload)

    assertThat(trigger1).isNotEqualTo(trigger2)
  }

  @Test
  fun `InteractionTriggerWithPayload toString contains payload`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"
    val payload = "test-payload"

    val trigger = InteractionTriggerWithPayload(triggerUptime, name, null, payload)
    val toString = trigger.toString()

    assertThat(toString).contains(name)
    assertThat(toString).contains(triggerUptime.toString())
    assertThat(toString).contains(payload)
    assertThat(toString).contains("InteractionTrigger")
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

    assertThat(trigger.payload).isEqualTo(payload)
    assertThat(trigger.payload.id).isEqualTo(42)
    assertThat(trigger.payload.description).isEqualTo("test description")
  }

  @Test
  fun `different trigger types with same name and time are not equal`() {
    val triggerUptime = 1000.nanoseconds
    val name = "test-trigger"

    val simpleTrigger = SimpleInteractionTrigger(triggerUptime, name)
    val payloadTrigger = InteractionTriggerWithPayload(triggerUptime, name, null, "payload")

    assertThat(simpleTrigger).isNotEqualTo(payloadTrigger)
  }

  @Test
  fun `InteractionTrace is created and handled correctly in triggerNow`() {
    val name = "test-trigger"

    val trigger = InteractionTrigger.triggerNow(name) as SimpleInteractionTrigger
    val trace = trigger.takeOverInteractionTrace()

    assertThat(trace).isNotNull()
    assertThat(trigger.takeOverInteractionTrace()).isNull()
  }

  @Test
  fun `InteractionTrigger duration handling`() {
    val millisDuration = 500.milliseconds
    val nanosDuration = millisDuration.inWholeNanoseconds.nanoseconds
    val name = "duration-test"

    val trigger = SimpleInteractionTrigger(millisDuration, name)

    assertThat(trigger.triggerUptime).isEqualTo(millisDuration)
    assertThat(trigger.triggerUptime).isEqualTo(nanosDuration)
  }

  @Test
  fun `InteractionTrace endTrace can be called`() {
    val trace = FakeInteractionTrace()
    assertThat(trace.endTraceCalled).isFalse()

    trace.endTrace()
    assertThat(trace.endTraceCalled).isTrue()
  }
}
