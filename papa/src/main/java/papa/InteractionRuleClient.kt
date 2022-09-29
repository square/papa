package papa

import android.os.SystemClock
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import android.view.InputEvent
import papa.TrackedInteraction.FinishingInteraction
import papa.TrackedInteraction.RunningInteraction
import papa.internal.checkMainThread
import papa.internal.isChoreographerDoingFrame
import papa.internal.isMainThread
import papa.internal.mainHandler
import papa.internal.onCurrentOrNextFrameRendered
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit.MILLISECONDS

interface InteractionRuleBuilder<EventType : Any, ParentInteractionType : Any> {
  fun <InteractionType : ParentInteractionType> addInteractionRule(block: InteractionScope<InteractionType, EventType>.() -> Unit): RemovableInteraction
}

fun interface RemovableInteraction {
  fun remove()
}

interface InteractionEventReceiver<EventType> {
  fun sendEvent(event: EventType)
}

class InteractionRuleClient<EventType : Any, ParentInteractionType : Any> : InteractionRuleBuilder<EventType, ParentInteractionType>, InteractionEventReceiver<EventType> {

  private val interactionEngines = mutableListOf<InteractionEngine<*, EventType>>()

  override fun <InteractionType : ParentInteractionType> addInteractionRule(block: InteractionScope<InteractionType, EventType>.() -> Unit): RemovableInteraction {
    checkMainThread()
    val engine = InteractionScope<InteractionType, EventType>().apply {
      block()
    }.buildInteractionEngine()
    interactionEngines += engine
    return RemovableInteraction {
      checkMainThread()
      interactionEngines -= engine
    }
  }

  override fun sendEvent(event: EventType) {
    val eventSentUptime = System.nanoTime().nanoseconds
    if (isMainThread) {
      for (engine in interactionEngines) {
        engine.sendEvent(event, eventSentUptime, InputTracker.triggerEvent)
      }
    } else {
      mainHandler.post {
        for (engine in interactionEngines) {
          engine.sendEvent(event, eventSentUptime, null)
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
  val onEventCallbacks = mutableListOf<Pair<Class<out ParentEventType>, OnEventScope<InteractionType>.(ParentEventType) -> Unit>>()

  @RuleMarker
  inline fun <reified EventType : ParentEventType> onEvent(noinline block: OnEventScope<InteractionType>.(EventType) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    onEventCallbacks.add(EventType::class.java to (block as OnEventScope<InteractionType>.(ParentEventType) -> Unit))
  }

  internal fun buildInteractionEngine(): InteractionEngine<*, ParentEventType> =
    InteractionEngine(this)
}

class InteractionEngine<InteractionType : Any, ParentEventType : Any>(interactionScope: InteractionScope<InteractionType, ParentEventType>) {

  private val onEventCallbacks: List<Pair<Class<out ParentEventType>, OnEventScope<InteractionType>.(ParentEventType) -> Unit>>

  private val runningInteractions = mutableListOf<RunningInteraction<InteractionType>>()
  private val finishingInteractions = mutableListOf<FinishingInteraction<InteractionType>>()

  inner class RealRunningInteraction(
    private val startingEventSentUptime: Duration,
    private val interactionInput: DeliveredInput<out InputEvent>?,
    private val trace: InteractionTrace,
    override var interaction: InteractionType,
    cancelTimeout: Duration,
    private val onCancel: (CanceledInteractionResult<InteractionType>) -> Unit
  ) : RunningInteraction<InteractionType>, FinishingInteraction<InteractionType>, FrameCallback {

    private var frameCount: Int = if (isChoreographerDoingFrame()) {
      1
    } else {
      0
    }

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
      frameCount++
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
      val cancelUptime = System.nanoTime().nanoseconds
      stopRunning()
      trace.endTrace()
      onCancel(
        CanceledInteractionResult(
          interaction = interaction,
          cancelReason = reason,
          interactionInput = interactionInput,
          runningDurationUptime = cancelUptime - startingEventSentUptime,
          frameCount = frameCount,
          startUptime = startingEventSentUptime
        )
      )
    }

    override fun finishOnFrameRendered(block: (InteractionLatencyResult<InteractionType>) -> Unit): FinishingInteraction<InteractionType> {
      stopRunning()
      finishingInteractions += this
      onCurrentOrNextFrameRendered { frameRenderedUptime ->
        choreographer.removeFrameCallback(this)
        trace.endTrace()

        val result = InteractionLatencyResult(
          interaction = interaction,
          interactionInput = interactionInput,
          displayDurationUptime = (frameRenderedUptime - startingEventSentUptime),
          frameCount = frameCount,
          startUptime = startingEventSentUptime
        )
        block(result)
      }
      return this
    }
  }

  init {
    onEventCallbacks = interactionScope.onEventCallbacks.toList()
  }

  fun sendEvent(
    event: ParentEventType,
    eventSentUptime: Duration,
    interactionInput: DeliveredInput<out InputEvent>?
  ) {
    val eventClassHierarchy = generateSequence<Class<*>>(event::class.java) {
      it.superclass
    }.toList()

    val realEventScope = object : OnEventScope<InteractionType> {
      override fun runningInteractions() = runningInteractions.toList().asSequence()

      override fun finishingInteractions() = finishingInteractions.toList().asSequence()

      override val interactionInput = interactionInput

      override fun cancelRunningInteractions(reason: String) {
        runningInteractions().forEach { it.cancel(reason) }
      }

      override fun startInteraction(
        interaction: InteractionType,
        trace: InteractionTrace,
        cancelTimeout: Duration,
        onCancel: (CanceledInteractionResult<InteractionType>) -> Unit
      ): RunningInteraction<InteractionType> {
        // If the interaction input trace end isn't taken over yet, end it.
        interactionInput?.takeOverTraceEnd()?.invoke()
        val runningInteraction = RealRunningInteraction(
          startingEventSentUptime = eventSentUptime,
          interactionInput = interactionInput,
          trace = trace,
          interaction = interaction,
          cancelTimeout,
          onCancel
        )
        runningInteractions += runningInteraction
        return runningInteraction
      }
    }

    onEventCallbacks.filter { (callbackEventClass, _) ->
      callbackEventClass in eventClassHierarchy
    }.forEach { (_, callback) ->
      realEventScope.callback(event)
    }
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
    onCancel: (CanceledInteractionResult<InteractionType>) -> Unit = { }
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
    fun finishOnFrameRendered(block: (InteractionLatencyResult<InteractionType>) -> Unit): FinishingInteraction<InteractionType>
  }

  interface FinishingInteraction<InteractionType : Any> : TrackedInteraction<InteractionType>
}

class CanceledInteractionResult<InteractionType>(
  val interaction: InteractionType,

  val cancelReason: String,

  /**
   * Interaction input that was automatically detected when the interaction started to be tracked,
   * if any.
   */
  val interactionInput: DeliveredInput<out InputEvent>?,

  /**
   * The time from [startUptime] to when the interaction was canceled.
   */
  val runningDurationUptime: Duration,

  /**
   * The number of frames that were rendered between the interaction start and when the
   * interaction was canceled.
   *
   */
  val frameCount: Int,

  /**
   * The uptime when the starting event was sent.
   */
  val startUptime: Duration

) {
  val totalFrameCount: Int
    get() = (interactionInput?.framesSinceDelivery ?: 0) + frameCount

  /**
   * The time from [InputEvent.getEventTime] to [DeliveredInput.deliveryUptime] if
   * [interactionInput] is not null, 0 otherwise.
   *
   * This represents the time from when an input event was detected by the system to when the
   * input event was handled by an app window.
   */
  val inputSystemDurationUptime: Duration
    get() = interactionInput?.run { deliveryUptime - eventUptime }
      ?: Duration.ZERO

  /**
   * The time from [DeliveredInput.deliveryUptime] to [startUptime] if
   * [interactionInput] is not null, 0 otherwise.
   *
   * This represents the time from when an input event was handled by an app window to when the
   * starting event of this interaction was sent.
   */
  val inputAppDurationUptime: Duration
    get() = interactionInput?.run { startUptime - deliveryUptime } ?: Duration.ZERO

  /**
   * This represents the total time a user has been waiting for the interaction until it was
   * canceled.
   */
  val totalDurationUptime: Duration
    get() = inputSystemDurationUptime + inputAppDurationUptime + runningDurationUptime

  override fun toString(): String {
    return "CanceledInteractionResult(" +
      "interaction=$interaction, " +
      "cancelReason=$cancelReason, " +
      "interactionInput=$interactionInput, " +
      "runningDuration=${runningDurationUptime.toString(MILLISECONDS)}, " +
      "frameCount=$frameCount, " +
      "totalFrameCount=$totalFrameCount, " +
      "startUptime=${startUptime.toString(MILLISECONDS)}, " +
      "inputSystemDuration=${inputSystemDurationUptime.toString(MILLISECONDS)}, " +
      "inputAppDuration=${inputAppDurationUptime.toString(MILLISECONDS)}, " +
      "totalDuration=${totalDurationUptime.toString(MILLISECONDS)}" +
      ")"
  }
}

class InteractionLatencyResult<InteractionType : Any>(
  val interaction: InteractionType,
  /**
   * Interaction input that was automatically detected when the interaction started to be tracked,
   * if any.
   */
  val interactionInput: DeliveredInput<out InputEvent>?,

  /**
   * The time from [startUptime] to when a frame was rendered after the interaction end.
   */
  val displayDurationUptime: Duration,

  /**
   * The number of frames that were rendered between the interaction start and the frame of the
   * interaction end. Always >= 1. [frameCount] is equal to 1 for single frame interactions.
   */
  val frameCount: Int,

  /**
   * The uptime when the starting event was sent.
   */
  val startUptime: Duration
) {

  val totalFrameCount: Int
    get() = (interactionInput?.framesSinceDelivery ?: 0) + frameCount

  /**
   * The time from [InputEvent.getEventTime] to [DeliveredInput.deliveryUptime] if
   * [interactionInput] is not null, 0 otherwise.
   *
   * This represents the time from when an input event was detected by the system to when the
   * input event was handled by an app window.
   */
  val inputSystemDurationUptime: Duration
    get() = interactionInput?.run { deliveryUptime - eventUptime }
      ?: Duration.ZERO

  /**
   * The time from [DeliveredInput.deliveryUptime] to [startUptime] if
   * [interactionInput] is not null, 0 otherwise.
   *
   * This represents the time from when an input event was handled by an app window to when the
   * starting event of this interaction was sent.
   */
  val inputAppDurationUptime: Duration
    get() = interactionInput?.run { startUptime - deliveryUptime } ?: Duration.ZERO

  /**
   * This represents the interaction time as it would be reported in a system trace, assuming
   * the interaction was started with the default [InteractionTrace.fromInputDelivered].
   */
  val traceDurationUptime: Duration
    get() = inputAppDurationUptime + displayDurationUptime

  /**
   * This represents the total time a user has been waiting for the interaction, i.e. this is the
   * real interaction latency.
   */
  val totalDurationUptime: Duration
    get() = inputSystemDurationUptime + inputAppDurationUptime + displayDurationUptime

  override fun toString(): String {
    return "InteractionLatencyResult(" +
      "interaction=$interaction, " +
      "interactionInput=$interactionInput, " +
      "displayDuration=${displayDurationUptime.toString(MILLISECONDS)}, " +
      "frameCount=$frameCount, " +
      "totalFrameCount=$totalFrameCount, " +
      "startUptime=${startUptime.toString(MILLISECONDS)}, " +
      "inputSystemDuration=${inputSystemDurationUptime.toString(MILLISECONDS)}, " +
      "inputAppDuration=${inputAppDurationUptime.toString(MILLISECONDS)}, " +
      "traceDuration=${traceDurationUptime.toString(MILLISECONDS)}, " +
      "totalDuration=${totalDurationUptime.toString(MILLISECONDS)}" +
      ")"
  }
}
