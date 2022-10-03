package papa

import android.os.SystemClock
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import android.view.InputEvent
import papa.TrackedInteraction.FinishingInteraction
import papa.TrackedInteraction.RunningInteraction
import papa.internal.checkMainThread
import papa.internal.isMainThread
import papa.internal.mainHandler
import papa.internal.onCurrentOrNextFrameRendered
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit.MILLISECONDS

interface InteractionRuleBuilder<ParentInteractionType : Any, EventType : Any> {
  fun <InteractionType : ParentInteractionType> addInteractionRule(block: InteractionScope<InteractionType, EventType>.() -> Unit): RemovableInteraction
}

fun interface InteractionResultListener<InteractionType : Any, EventType : Any> {
  fun onInteractionResult(result: InteractionResult<InteractionType, EventType>)
}

fun interface RemovableInteraction {
  fun remove()
}

interface InteractionEventSink<EventType> {
  fun sendEvent(event: EventType)
}

class InteractionRuleClient<ParentInteractionType : Any, EventType : Any>(
  private val resultListener: InteractionResultListener<ParentInteractionType, EventType>
) : InteractionRuleBuilder<ParentInteractionType, EventType>, InteractionEventSink<EventType> {

  private val interactionEngines = mutableListOf<InteractionEngine<*, *, EventType>>()

  override fun <InteractionType : ParentInteractionType> addInteractionRule(block: InteractionScope<InteractionType, EventType>.() -> Unit): RemovableInteraction {
    checkMainThread()
    val interactionScope = InteractionScope<InteractionType, EventType>().apply {
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
class InteractionScope<InteractionType : Any, ParentEventType : Any> {

  // Public because onEvent is inline (to capture the reified event type).
  val onEventCallbacks =
    mutableListOf<Pair<Class<out ParentEventType>, OnEventScope<InteractionType>.(ParentEventType) -> Unit>>()

  @RuleMarker
  inline fun <reified EventType : ParentEventType> onEvent(noinline block: OnEventScope<InteractionType>.(EventType) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    onEventCallbacks.add(EventType::class.java to (block as OnEventScope<InteractionType>.(ParentEventType) -> Unit))
  }
}

private class InteractionEngine<ParentInteractionType : Any, InteractionType : ParentInteractionType, ParentEventType : Any>(
  private val resultListener: InteractionResultListener<ParentInteractionType, ParentEventType>,
  interactionScope: InteractionScope<InteractionType, ParentEventType>
) {

  private val onEventCallbacks: List<Pair<Class<out ParentEventType>, OnEventScope<InteractionType>.(ParentEventType) -> Unit>>

  private val runningInteractions = mutableListOf<RunningInteraction<InteractionType>>()
  private val finishingInteractions = mutableListOf<FinishingInteraction<InteractionType>>()

  private var eventInScope: SentEvent<ParentEventType>? = null

  inner class RealRunningInteraction(
    private val start: SentEvent<ParentEventType>,
    private val interactionInput: DeliveredInput<out InputEvent>?,
    private val trace: InteractionTrace,
    override var interaction: InteractionType,
    cancelTimeout: Duration
  ) : RunningInteraction<InteractionType>, FinishingInteraction<InteractionType>, FrameCallback {

    private var frameCountSinceStart: Int = 0

    private lateinit var end: SentEvent<ParentEventType>

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
    }

    private fun stopRunning() {
      check(runningInteractions.remove(this)) {
        "$interaction not running"
      }
      mainHandler.removeCallbacks(cancelOnTimeout)
      choreographer.removeFrameCallback(this)
    }

    override fun cancel(reason: String) {
      val end = eventInScope
      val cancelUptime = end?.uptime ?: System.nanoTime().nanoseconds
      stopRunning()
      trace.endTrace()
      resultListener.onInteractionResult(
        InteractionResult.Canceled(
          data = InteractionResultDataPayload(
            interaction = interaction,
            interactionInput = interactionInput,
            runningFrameCount = frameCountSinceStart,
            start = start,
          ),
          end = end,
          cancelUptime = cancelUptime,
          cancelReason = reason
        )
      )
    }

    override fun finish(): FinishingInteraction<InteractionType> {
      stopRunning()
      finishingInteractions += this
      updateEndEvent()
      onCurrentOrNextFrameRendered { frameRenderedUptime ->
        choreographer.removeFrameCallback(this)
        trace.endTrace()
        resultListener.onInteractionResult(
          InteractionResult.Finished(
            data = InteractionResultDataPayload(
              interaction = interaction,
              interactionInput = interactionInput,
              runningFrameCount = frameCountSinceStart,
              start = start,
            ),
            end = end,
            endFrameRenderedUptime = frameRenderedUptime
          )
        )
      }
      return this
    }

    override fun updateEndEvent() {
      end = eventInScope!!
    }
  }

  init {
    onEventCallbacks = interactionScope.onEventCallbacks.toList()
  }

  fun sendEvent(
    sentEvent: SentEvent<ParentEventType>,
    interactionInput: DeliveredInput<out InputEvent>?
  ) {
    val eventClassHierarchy = generateSequence<Class<*>>(sentEvent.event::class.java) {
      it.superclass
    }.toList()

    val realEventScope = object : OnEventScope<InteractionType> {
      override fun runningInteractions() = runningInteractions.toList().asSequence()

      override fun finishingInteractions() = finishingInteractions.toList().asSequence()

      override val interactionInput = interactionInput

      override fun cancelRunningInteractions(reason: String) {
        this@InteractionEngine.cancelRunningInteractions(reason)
      }

      override fun startInteraction(
        interaction: InteractionType,
        trace: InteractionTrace,
        cancelTimeout: Duration,
      ): RunningInteraction<InteractionType> {
        // If the interaction input trace end isn't taken over yet, end it.
        interactionInput?.takeOverTraceEnd()?.invoke()
        val runningInteraction = RealRunningInteraction(
          start = sentEvent,
          interactionInput = interactionInput,
          trace = trace,
          interaction = interaction,
          cancelTimeout
        )
        runningInteractions += runningInteraction
        return runningInteraction
      }
    }

    eventInScope = sentEvent
    onEventCallbacks.filter { (callbackEventClass, _) ->
      callbackEventClass in eventClassHierarchy
    }.forEach { (_, callback) ->
      realEventScope.callback(sentEvent.event)
    }
    eventInScope = null
  }

  fun cancelRunningInteractions(reason: String) {
    // Copy list as cancel mutates the backing list.
    runningInteractions.toList().asSequence().forEach { it.cancel(reason) }
  }
}

const val CANCEL_REASON_NOT_PROVIDED = "reason not provided"
const val CANCEL_ALL_REASON_NOT_PROVIDED = "canceled all interactions, reason not provided"

@RuleMarker
interface OnEventScope<InteractionType : Any> {
  fun runningInteractions(): Sequence<RunningInteraction<InteractionType>>
  fun finishingInteractions(): Sequence<FinishingInteraction<InteractionType>>

  val interactionInput: DeliveredInput<out InputEvent>?

  fun cancelRunningInteractions(reason: String = CANCEL_ALL_REASON_NOT_PROVIDED)

  fun startInteraction(
    interaction: InteractionType,
    trace: InteractionTrace = InteractionTrace.fromInputDelivered(interaction, interactionInput),
    cancelTimeout: Duration = 1.minutes,
  ): RunningInteraction<InteractionType>
}

fun interface InteractionTrace {

  fun endTrace()

  companion object {
    fun fromInputDelivered(
      interaction: Any,
      interactionInput: DeliveredInput<out InputEvent>?
    ): InteractionTrace {
      val endTrace = interactionInput?.takeOverTraceEnd()
      return if (endTrace != null) {
        InteractionTrace {
          endTrace()
        }
      } else {
        fromNow(interaction.toString())
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

sealed interface TrackedInteraction<InteractionType : Any> {
  var interaction: InteractionType

  interface RunningInteraction<InteractionType : Any> : TrackedInteraction<InteractionType> {
    fun cancel(reason: String = CANCEL_REASON_NOT_PROVIDED)
    fun finish(): FinishingInteraction<InteractionType>
  }

  interface FinishingInteraction<InteractionType : Any> : TrackedInteraction<InteractionType> {
    /**
     * Updates the end event of this interaction to be the current event.
     */
    fun updateEndEvent()
  }
}

interface InteractionResultData<InteractionType : Any, EventType : Any> {
  val interaction: InteractionType

  /**
   * Interaction input that was automatically detected when the interaction started to be tracked,
   * if any.
   */
  val interactionInput: DeliveredInput<out InputEvent>?

  /**
   * The number of frames that were rendered between the [start] and [end]
   */
  val runningFrameCount: Int

  val start: SentEvent<EventType>
}

class SentEvent<EventType : Any>(
  val uptime: Duration,
  val event: EventType
)

class InteractionResultDataPayload<InteractionType : Any, EventType : Any>(
  override val interaction: InteractionType,
  override val interactionInput: DeliveredInput<out InputEvent>?,
  override val runningFrameCount: Int,
  override val start: SentEvent<EventType>,
) : InteractionResultData<InteractionType, EventType>

sealed class InteractionResult<InteractionType : Any, EventType : Any>(
  data: InteractionResultData<InteractionType, EventType>
) : InteractionResultData<InteractionType, EventType> by data {

  /**
   * An interaction that was started and then canceled.
   */
  class Canceled<InteractionType : Any, EventType : Any>(
    data: InteractionResultData<InteractionType, EventType>,
    val cancelReason: String,
    val end: SentEvent<EventType>?,
    val cancelUptime: Duration
  ) : InteractionResult<InteractionType, EventType>(data) {
    val startToCancel: Duration
      get() = cancelUptime - start.uptime
  }

  /**
   * An interaction that was started, finished and the UI change was visible to the user
   * (frame rendered).
   */
  class Finished<InteractionType : Any, EventType : Any>(
    data: InteractionResultData<InteractionType, EventType>,
    val end: SentEvent<EventType>,
    val endFrameRenderedUptime: Duration
  ) : InteractionResult<InteractionType, EventType>(data) {
    val startToEndFrameRendered: Duration
      get() = endFrameRenderedUptime - start.uptime
  }

  override fun toString(): String {
    return buildString {
      append("InteractionResult.${this@InteractionResult::class.java.simpleName}")
      append("(")
      append("interaction=$interaction, ")
      append(
        when (this@InteractionResult) {
          is Canceled<*, *> -> "cancelReason=\"$cancelReason\", startToCancel=${
            startToCancel.toString(MILLISECONDS)
          }, "
          is Finished<*, *> -> "startToEndFrameRendered=${
            startToEndFrameRendered.toString(MILLISECONDS)
          }, "
        }
      )
      append("runningFrameCount=$runningFrameCount, ")
      append("start.event=${start.event}, ")
      append(
        when (this@InteractionResult) {
          is Canceled<*, *> -> "end.event=${end?.event}, "
          is Finished<*, *> -> "end.event=${end.event}, "
        }
      )
      interactionInput?.let {
        append(
          "inputToStart=${
            (start.uptime - it.eventUptime).toString(
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
