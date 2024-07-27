package papa

import android.os.SystemClock
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import android.view.InputEvent
import papa.internal.checkMainThread
import papa.internal.isMainThread
import papa.internal.mainHandler
import papa.internal.onCurrentOrNextFrameRendered
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit.MILLISECONDS

interface InteractionRuleBuilder<EventType : Any> {
  fun addInteractionRule(block: InteractionScope<EventType>.() -> Unit): RemovableInteraction
}

fun interface InteractionResultListener<EventType : Any> {
  fun onInteractionResult(result: InteractionResult<EventType>)
}

fun interface RemovableInteraction {
  fun remove()
}

interface InteractionEventSink<EventType> {
  fun sendEvent(event: EventType)
}

class InteractionRuleClient<EventType : Any>(
  private val resultListener: InteractionResultListener<EventType>,
) : InteractionRuleBuilder<EventType>, InteractionEventSink<EventType> {

  private val interactionEngines = mutableListOf<InteractionEngine<EventType>>()

  val trackedInteractions: List<TrackedInteraction<EventType>>
    get() {
      checkMainThread()
      return interactionEngines.flatMap { it.trackedInteractions }
    }

  override fun addInteractionRule(block: InteractionScope<EventType>.() -> Unit): RemovableInteraction {
    checkMainThread()
    val interactionScope = InteractionScope<EventType>().apply {
      block()
    }
    val engine = InteractionEngine(resultListener, interactionScope)
    interactionEngines += engine
    return RemovableInteraction {
      checkMainThread()
      engine.cancelRunningInteractions("Rule removed")
      interactionEngines -= engine
    }
  }

  override fun sendEvent(event: EventType) {
    val eventSentUptime = System.nanoTime().nanoseconds
    val sentEvent = SentEvent(eventSentUptime, event)
    if (isMainThread) {
      for (engine in interactionEngines) {
        engine.sendEvent(sentEvent, InputTracker.triggerEvent)
      }
    } else {
      mainHandler.post {
        for (engine in interactionEngines) {
          // interactionInput is null as the event was sent from a background thread so there's
          // no way the event can be tied back to an input event.
          engine.sendEvent(sentEvent, interactionInput = null)
        }
      }
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
  private val resultListener: InteractionResultListener<ParentEventType>,
  interactionScope: InteractionScope<ParentEventType>
) {

  private val onEventCallbacks: Map<Class<out ParentEventType>, List<OnEventScope<ParentEventType, ParentEventType>.() -> Unit>>

  private val runningInteractions = mutableListOf<RunningInteraction<ParentEventType>>()
  private val finishingInteractions = mutableListOf<FinishingInteraction<ParentEventType>>()

  val trackedInteractions: List<TrackedInteraction<ParentEventType>>
    get() = runningInteractions + finishingInteractions

  private var eventInScope: SentEvent<ParentEventType>? = null

  inner class RealRunningInteraction(
    override val interactionInput: InteractionStartInput?,
    private val trace: InteractionTrace,
    cancelTimeout: Duration
  ) : RunningInteraction<ParentEventType>, FinishingInteraction<ParentEventType>, FrameCallback {

    private var frameCountSinceStart: Int = 0

    override val sentEvents = mutableListOf<SentEvent<ParentEventType>>()

    /**
     * Note: this must implement [Runnable]. A lambda would compile fine but then be wrapped into
     * a runnable on each usage site, which means [android.os.Handler.removeCallbacks] would be
     * called with an unknown [Runnable].
     */
    private val cancelOnTimeout: Runnable = Runnable {
      cancel("Timeout after $cancelTimeout")
    }

    private val choreographer = Choreographer.getInstance()

    override fun doFrame(frameTimeNanos: Long) {
      frameCountSinceStart++
      choreographer.postFrameCallback(this)
    }

    init {
      choreographer.postFrameCallback(this)
      mainHandler.postDelayed(cancelOnTimeout, cancelTimeout.inWholeMilliseconds)
      recordEvent()
    }

    private fun stopRunning() {
      check(runningInteractions.remove(this)) {
        "Interaction started by ${sentEvents.first()} and ended by ${sentEvents.last()} is not running."
      }
      mainHandler.removeCallbacks(cancelOnTimeout)
      choreographer.removeFrameCallback(this)
    }

    override fun cancel(reason: String) {
      val cancelUptime = eventInScope?.uptime ?: System.nanoTime().nanoseconds
      stopRunning()
      trace.endTrace()
      val eventsCopy = sentEvents.toList()
      resultListener.onInteractionResult(
        InteractionResult.Canceled(
          data = InteractionResultDataPayload(
            interactionInput = interactionInput,
            runningFrameCount = frameCountSinceStart,
            sentEvents = eventsCopy,
          ),
          cancelUptime = cancelUptime,
          cancelReason = reason
        )
      )
    }

    override fun finish(): FinishingInteraction<ParentEventType> {
      stopRunning()
      finishingInteractions += this
      recordEvent()
      onCurrentOrNextFrameRendered { frameRenderedUptime ->
        trace.endTrace()
        choreographer.removeFrameCallback(this)
        finishingInteractions -= this
        val eventsCopy = sentEvents.toList()
        resultListener.onInteractionResult(
          InteractionResult.Finished(
            data = InteractionResultDataPayload(
              interactionInput = interactionInput,
              runningFrameCount = frameCountSinceStart,
              sentEvents = eventsCopy,
            ),
            endFrameRenderedUptime = frameRenderedUptime
          )
        )
      }
      return this
    }

    override fun recordEvent() {
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
    interactionInput: DeliveredInput<out InputEvent>?
  ) {

    val realEventScope = object : OnEventScope<ParentEventType, ParentEventType> {
      override fun runningInteractions() = runningInteractions.toList()

      override fun finishingInteractions() = finishingInteractions.toList()

      override val interactionInput = interactionInput

      override fun startInteraction(
        interactionStartInput: InteractionStartInput?,
        trace: InteractionTrace,
        cancelTimeout: Duration,
      ): RunningInteraction<ParentEventType> {
        // If the interaction input trace end isn't taken over yet, end it.
        interactionInput?.takeOverTraceEnd()?.invoke()

        val runningInteraction = RealRunningInteraction(
          // Default to custom interactionStartInput if passed in, otherwise default to delivered
          // input (key or tap)
          interactionInput = interactionStartInput ?: interactionInput,
          trace = trace,
          cancelTimeout
        )
        runningInteractions += runningInteraction
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

  val interactionInput: DeliveredInput<out InputEvent>?

  val event: EventType

  fun startInteraction(
    interactionStartInput: InteractionStartInput? = null,
    trace: InteractionTrace = InteractionTrace.fromInputDelivered(event, interactionInput),
    cancelTimeout: Duration = 1.minutes,
  ): RunningInteraction<ParentEventType>

  /**
   * A utility method to record an interaction that is started by the current event and is also
   * immediately finished. This mean the interaction duration will be measured as the sending of
   * the event until the next frame.
   */
  fun recordSingleFrameInteraction(
    trace: InteractionTrace = InteractionTrace.fromInputDelivered(event, interactionInput),
  ): FinishingInteraction<ParentEventType> {
    return startInteraction(trace = trace).finish()
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

fun interface InteractionTrace {

  fun endTrace()

  companion object {
    fun fromInputDelivered(
      event: Any,
      interactionInput: DeliveredInput<out InputEvent>?
    ): InteractionTrace {
      val endTrace = interactionInput?.takeOverTraceEnd()
      return if (endTrace != null) {
        InteractionTrace {
          endTrace()
        }
      } else {
        fromNow(event.toString())
      }
    }

    fun fromNow(label: String): InteractionTrace {
      val cookie = SystemClock.uptimeMillis().rem(Int.MAX_VALUE).toInt()
      SafeTrace.beginAsyncSection(label, cookie)
      return InteractionTrace {
        SafeTrace.endAsyncSection(label, cookie)
      }
    }
  }
}

interface TrackedInteraction<EventType : Any> {
  val sentEvents: List<SentEvent<EventType>>
  val interactionInput: InteractionStartInput?

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

interface InteractionResultData<EventType : Any> {
  /**
   * Interaction input that was automatically detected when the interaction started to be tracked,
   * if any.
   */
  val interactionInput: InteractionStartInput?

  /**
   * The number of frames that were rendered between the first and the last event in [sentEvents]
   */
  val runningFrameCount: Int

  val sentEvents: List<SentEvent<EventType>>
}

class SentEvent<EventType : Any>(
  val uptime: Duration,
  val event: EventType
)

class InteractionResultDataPayload<EventType : Any>(
  override val interactionInput: InteractionStartInput?,
  override val runningFrameCount: Int,
  override val sentEvents: List<SentEvent<EventType>>,
) : InteractionResultData<EventType>

sealed class InteractionResult<EventType : Any>(
  data: InteractionResultData<EventType>
) : InteractionResultData<EventType> by data {

  /**
   * An interaction that was started and then canceled.
   */
  class Canceled<EventType : Any>(
    data: InteractionResultData<EventType>,
    val cancelReason: String,
    val cancelUptime: Duration
  ) : InteractionResult<EventType>(data) {
    val startToCancel: Duration
      get() = cancelUptime - sentEvents.first().uptime
  }

  /**
   * An interaction that was started, finished and the UI change was visible to the user
   * (frame rendered).
   */
  class Finished<EventType : Any>(
    data: InteractionResultData<EventType>,
    val endFrameRenderedUptime: Duration
  ) : InteractionResult<EventType>(data) {
    val startToEndFrameRendered: Duration
      get() = endFrameRenderedUptime - sentEvents.first().uptime
  }

  override fun toString(): String {
    return buildString {
      append("InteractionResult.${this@InteractionResult::class.java.simpleName}")
      append("(")
      append(
        when (this@InteractionResult) {
          is Canceled<*> -> "cancelReason=\"$cancelReason\", startToCancel=${
            startToCancel.toString(MILLISECONDS)
          }, "

          is Finished<*> -> "startToEndFrameRendered=${
            startToEndFrameRendered.toString(MILLISECONDS)
          }, "
        }
      )
      append("runningFrameCount=$runningFrameCount, ")
      append("events=${sentEvents.map { it.event }}, ")
      interactionInput?.let {
        append(
          "inputToStart=${
            (sentEvents.first().uptime - it.inputUptime).toString(
              MILLISECONDS
            )
          }, "
        )
      }
      append("interactionInput=$interactionInput")
      append(")")
    }
  }
}
