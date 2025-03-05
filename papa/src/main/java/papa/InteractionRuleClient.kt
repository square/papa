package papa

import papa.InteractionUpdate.CancelOnEvent
import papa.InteractionUpdate.CancelOnTimeout
import papa.InteractionUpdate.Finish
import papa.InteractionUpdate.RecordEvent
import papa.InteractionUpdate.Rendered
import papa.InteractionUpdate.Start
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

interface InteractionRuleBuilder<EventType : Any> {
  fun addInteractionRule(block: InteractionScope<EventType>.() -> Unit): RemovableInteraction
}

sealed interface InteractionUpdate<EventType : Any> {
  val interaction: InteractionInFlight<EventType>

  sealed interface WithEvent<EventType : Any> : InteractionUpdate<EventType> {
    val event: SentEvent<EventType>
  }

  data class Start<EventType : Any>(
    override val event: SentEvent<EventType>,
    override val interaction: InteractionInFlight<EventType>
  ) : WithEvent<EventType>

  data class RecordEvent<EventType : Any>(
    override val event: SentEvent<EventType>,
    override val interaction: InteractionInFlight<EventType>
  ) : WithEvent<EventType>

  data class CancelOnEvent<EventType : Any>(
    override val event: SentEvent<EventType>,
    override val interaction: InteractionInFlight<EventType>,
    val reason: String
  ) : WithEvent<EventType>

  data class CancelOnTimeout<EventType : Any>(
    val timeout: Duration,
    override val interaction: InteractionInFlight<EventType>
  ) : InteractionUpdate<EventType>

  data class Finish<EventType : Any>(
    override val event: SentEvent<EventType>,
    override val interaction: InteractionInFlight<EventType>
  ) : WithEvent<EventType>

  data class Rendered<EventType : Any>(
    override val event: SentEvent<EventType>,
    override val interaction: InteractionInFlight<EventType>,
    val frameRenderedUptime: Duration
  ) : WithEvent<EventType>
}

fun interface InteractionUpdateListener<EventType : Any> {
  fun onInteractionUpdate(update: InteractionUpdate<EventType>)
}

fun interface RemovableInteraction {
  fun remove()
}

interface InteractionEventSink<EventType> {
  fun sendEvent(event: EventType)
}

class InteractionRuleClient<EventType : Any>(
  private val updateListener: InteractionUpdateListener<EventType>,
) : InteractionRuleBuilder<EventType>, InteractionEventSink<EventType> {

  private val interactionEngines = mutableListOf<InteractionEngine<EventType>>()

  val trackedInteractions: List<InteractionInFlight<EventType>>
    get() {
      Handlers.checkOnMainThread()
      return interactionEngines.flatMap { it.trackedInteractions }
    }

  override fun addInteractionRule(block: InteractionScope<EventType>.() -> Unit): RemovableInteraction {
    Handlers.checkOnMainThread()
    val interactionScope = InteractionScope<EventType>().apply {
      block()
    }
    val engine = InteractionEngine(updateListener, interactionScope)
    interactionEngines += engine
    return RemovableInteraction {
      Handlers.checkOnMainThread()
      engine.cancelRunningInteractions("Rule removed")
      interactionEngines -= engine
    }
  }

  override fun sendEvent(event: EventType) {
    val eventSentUptime = System.nanoTime().nanoseconds
    val sentEvent = SentEvent(eventSentUptime, event)
    val sendEvent = {
      for (engine in interactionEngines) {
        engine.sendEvent(sentEvent)
      }
    }
    if (Handlers.isOnMainThread) {
      sendEvent()
    } else {
      Handlers.mainThreadHandler.post(sendEvent)
    }
  }
}

@DslMarker
annotation class RuleMarker

@RuleMarker
class InteractionScope<ParentEventType : Any> {

  // Public because onEvent is inline (to capture the reified event type).
  val onEventCallbacks =
    mutableListOf<Pair<Class<out ParentEventType>, OnEventScope<ParentEventType, ParentEventType>.() -> Unit>>()

  @RuleMarker
  inline fun <reified EventType : ParentEventType> onEvent(noinline block: OnEventScope<ParentEventType, EventType>.() -> Unit) {
    @Suppress("UNCHECKED_CAST")
    onEventCallbacks.add(
      EventType::class.java to (block as OnEventScope<ParentEventType, ParentEventType>.() -> Unit)
    )
  }
}

private class InteractionEngine<ParentEventType : Any>(
  private val updateListener: InteractionUpdateListener<ParentEventType>,
  interactionScope: InteractionScope<ParentEventType>
) {

  private val onEventCallbacks: Map<Class<out ParentEventType>, List<OnEventScope<ParentEventType, ParentEventType>.() -> Unit>>

  private val runningInteractions = mutableListOf<RunningInteraction<ParentEventType>>()
  private val finishingInteractions = mutableListOf<FinishingInteraction<ParentEventType>>()

  val trackedInteractions: List<TrackedInteraction<ParentEventType>>
    get() = runningInteractions + finishingInteractions

  private var eventInScope: SentEvent<ParentEventType>? = null

  inner class RealRunningInteraction(
    override val interactionTrigger: InteractionTrigger?,
    private val trace: InteractionTrace,
    cancelTimeout: Duration
  ) : RunningInteraction<ParentEventType>, FinishingInteraction<ParentEventType> {

    override val sentEvents = mutableListOf<SentEvent<ParentEventType>>()

    /**
     * Note: this must implement [Runnable]. A lambda would compile fine but then be wrapped into
     * a runnable on each usage site, which means [android.os.Handler.removeCallbacks] would be
     * called with an unknown [Runnable].
     */
    private val cancelOnTimeout: Runnable = Runnable {
      SafeTrace.logSection {
        "PAPA-cancel:timeout"
      }
      stopRunning()
      trace.endTrace()
      updateListener.onInteractionUpdate(CancelOnTimeout(cancelTimeout, this))
    }

    init {
      Handlers.mainThreadHandler.postDelayed(cancelOnTimeout, cancelTimeout.inWholeMilliseconds)
      addRecordedEvent()
    }

    private fun stopRunning() {
      check(runningInteractions.remove(this)) {
        "Interaction started by ${sentEvents.first()} and ended by ${sentEvents.last()} is not running."
      }
      Handlers.mainThreadHandler.removeCallbacks(cancelOnTimeout)
    }

    override fun cancel(reason: String) {
      val sentEvent = eventInScope!!
      SafeTrace.logSection {
        "PAPA-cancel:${sentEvent.event}:$reason"
      }
      stopRunning()
      trace.endTrace()
      updateListener.onInteractionUpdate(CancelOnEvent(sentEvent, this, reason))
    }

    override fun finish(): FinishingInteraction<ParentEventType> {
      val sentEvent = eventInScope!!
      SafeTrace.logSection {
        "PAPA-finishInteraction:${sentEvent.event}"
      }
      stopRunning()
      finishingInteractions += this
      addRecordedEvent()
      updateListener.onInteractionUpdate(Finish(sentEvent, this))

      // When compiling with Java11 we get AbstractMethodError at runtime when this is a lambda.
      @Suppress("ObjectLiteralToLambda")
      Choreographers.postOnFrameRendered(object : OnFrameRenderedListener {
        override fun onFrameRendered(frameRenderedUptime: Duration) {
          trace.endTrace()
          val interaction = this@RealRunningInteraction
          finishingInteractions -= interaction
          updateListener.onInteractionUpdate(Rendered(sentEvent, interaction, frameRenderedUptime))
        }
      })
      return this
    }

    override fun recordEvent() {
      val sentEvent = eventInScope!!
      SafeTrace.logSection {
        "PAPA-recordEvent:${sentEvent.event}"
      }
      addRecordedEvent()
      updateListener.onInteractionUpdate(RecordEvent(sentEvent, this))
    }

    private fun addRecordedEvent() {
      val recordedSentEvent = eventInScope!!
      if (sentEvents.lastOrNull()?.event !== recordedSentEvent.event) {
        sentEvents += recordedSentEvent
      }
    }
  }

  init {
    val eventCallbackPairs = interactionScope.onEventCallbacks.toList()
    // A lazy map where each key is a concrete event type. Every time it gets asked about a new
    // event type, it figures out which callbacks are assignable from that event type then builds
    // and caches that list.
    onEventCallbacks =
      mutableMapOf<
        Class<out ParentEventType>,
        List<OnEventScope<ParentEventType, ParentEventType>.() -> Unit>
        >().withDefault { eventType ->
        eventCallbackPairs.filter { it.first.isAssignableFrom(eventType) }.map { it.second }
      }
  }

  fun sendEvent(
    sentEvent: SentEvent<ParentEventType>,
  ) {

    val realEventScope = object : OnEventScope<ParentEventType, ParentEventType> {
      override fun runningInteractions() = runningInteractions.toList()

      override fun finishingInteractions() = finishingInteractions.toList()

      override fun startInteraction(
        trigger: InteractionTrigger?,
        trace: InteractionTrace,
        cancelTimeout: Duration,
      ): RunningInteraction<ParentEventType> {
        SafeTrace.logSection {
          "PAPA-startInteraction:${sentEvent.event}"
        }
        val runningInteraction = RealRunningInteraction(
          interactionTrigger = trigger,
          trace = trace,
          cancelTimeout
        )
        runningInteractions += runningInteraction
        updateListener.onInteractionUpdate(Start(sentEvent, runningInteraction))
        return runningInteraction
      }

      override val event: ParentEventType
        get() = eventInScope!!.event
    }

    eventInScope = sentEvent
    onEventCallbacks.getValue(sentEvent.event::class.java).forEach { callback ->
      realEventScope.callback()
    }
    eventInScope = null
  }

  fun cancelRunningInteractions(reason: String) {
    // Copy list as cancel mutates the backing list.
    runningInteractions.toList().forEach { it.cancel(reason) }
  }
}

@RuleMarker
interface OnEventScope<ParentEventType : Any, EventType : ParentEventType> {
  fun runningInteractions(): List<RunningInteraction<ParentEventType>>
  fun finishingInteractions(): List<FinishingInteraction<ParentEventType>>

  val event: EventType

  fun startInteraction(
    trigger: InteractionTrigger? = MainThreadTriggerStack.earliestInteractionTrigger,
    trace: InteractionTrace = trigger?.takeOverInteractionTrace() ?: InteractionTrace.startNow(
      event.toString()
    ),
    cancelTimeout: Duration = 1.minutes,
  ): RunningInteraction<ParentEventType>

  /**
   * A utility method to record an interaction that is started by the current event and is also
   * immediately finished. This mean the interaction duration will be measured as the sending of
   * the event until the next frame.
   */
  fun recordSingleFrameInteraction(
    trigger: InteractionTrigger? = MainThreadTriggerStack.earliestInteractionTrigger,
    trace: InteractionTrace = trigger?.takeOverInteractionTrace() ?: InteractionTrace.startNow(
      event.toString()
    ),
  ): FinishingInteraction<ParentEventType> {
    return startInteraction(
      trigger = trigger,
      trace = trace
    ).finish()
  }

  /**
   * A utility method to cancel a single interaction and derive the reason from the event from
   * which cancel was called.
   */
  fun RunningInteraction<ParentEventType>.cancel() {
    cancel(event.toString())
  }

  /**
   * A utility method to cancel all running interactions **for the current interaction rule**. This
   * does not cancel interactions started by other rules. This method is useful when a particular
   * rule wants to ensure only a single interaction is running at any given time.
   */
  fun cancelRunningInteractions(
    reason: String = event.toString()
  ) {
    // Copy list as cancel mutates the backing list.
    for (interaction in runningInteractions()) {
      interaction.cancel(reason)
    }
  }
}

interface InteractionInFlight<EventType : Any> {
  /**
   * List of events recorded for this interaction. This list is dynamically updated from the
   * main thread as new events get tied to this interaction, and isn't thread safe, so avoid
   * switching threads with this and make copies instead.
   */
  val sentEvents: List<SentEvent<EventType>>
  val interactionTrigger: InteractionTrigger?
}

interface TrackedInteraction<EventType : Any> : InteractionInFlight<EventType> {
  /**
   * Adds the current event instance to the list of events (if not already added).
   * Useful for both [RunningInteraction] & [FinishingInteraction] (to record new events
   * that are relevant and happen after calling finish but before the next frame)
   */
  fun recordEvent()
}

interface RunningInteraction<EventType : Any> : TrackedInteraction<EventType> {
  fun cancel(reason: String)
  fun finish(): FinishingInteraction<EventType>
}

interface FinishingInteraction<EventType : Any> : TrackedInteraction<EventType>

class SentEvent<EventType : Any>(
  val uptime: Duration,
  val event: EventType
)
