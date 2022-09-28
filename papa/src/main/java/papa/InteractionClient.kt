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
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit.MILLISECONDS

interface InteractionBuilder<E : Any> {
  fun <I : Interaction> addInteraction(block: InteractionScope<I, E>.() -> Unit): RemovableInteraction
}

fun interface RemovableInteraction {
  fun remove()
}

interface InteractionEventReporter<E> {
  fun sendEvent(event: E)
}

class InteractionClient<E : Any> : InteractionBuilder<E>, InteractionEventReporter<E> {

  private val interactionEngines = mutableListOf<InteractionEngine<*, E>>()

  override fun <I : Interaction> addInteraction(block: InteractionScope<I, E>.() -> Unit): RemovableInteraction {
    checkMainThread()
    val engine = InteractionScope<I, E>().apply {
      block()
    }.buildInteractionEngine()
    interactionEngines += engine
    return RemovableInteraction {
      checkMainThread()
      interactionEngines -= engine
    }
  }

  override fun sendEvent(event: E) {
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
class InteractionScope<I : Interaction, P : Any> {

  // Public because onEvent is inline (to capture the reified event type).
  val onEventCallbacks = mutableListOf<Pair<Class<out P>, OnEventScope<I>.(P) -> Unit>>()

  @RuleMarker
  inline fun <reified E : P> onEvent(noinline block: OnEventScope<I>.(E) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    onEventCallbacks.add(E::class.java to (block as OnEventScope<I>.(P) -> Unit))
  }

  internal fun buildInteractionEngine(): InteractionEngine<*, P> =
    InteractionEngine(this)
}

class InteractionEngine<I : Interaction, E : Any>(interactionScope: InteractionScope<I, E>) {

  private val onEventCallbacks: List<Pair<Class<out E>, OnEventScope<I>.(E) -> Unit>>

  private val runningInteractions = mutableListOf<RunningInteraction<I>>()
  private val finishingInteractions = mutableListOf<FinishingInteraction<I>>()

  inner class RealRunningInteraction(
    private val startingEventSentUptime: Duration,
    private val interactionInput: DeliveredInput<out InputEvent>?,
    private val trace: InteractionTrace,
    override var interaction: I,
    cancelTimeoutMillis: Long,
    private val onCancel: (CanceledInteractionResult<I>) -> Unit
  ) : RunningInteraction<I>, FinishingInteraction<I>, FrameCallback {

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
      cancel("Timeout after $cancelTimeoutMillis ms")
    }

    private val choreographer = Choreographer.getInstance()

    override fun doFrame(frameTimeNanos: Long) {
      frameCount++
      choreographer.postFrameCallback(this)
    }

    init {
      choreographer.postFrameCallback(this)
      mainHandler.postDelayed(cancelOnTimeout, cancelTimeoutMillis)
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

    override fun finishOnFrameRendered(block: (InteractionLatencyResult<I>) -> Unit): FinishingInteraction<I> {
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
    event: E,
    eventSentUptime: Duration,
    interactionInput: DeliveredInput<out InputEvent>?
  ) {
    val eventClassHierarchy = generateSequence<Class<*>>(event::class.java) {
      it.superclass
    }.toList()

    val realEventScope = object : OnEventScope<I> {
      override fun runningInteractions() = runningInteractions.toList().asSequence()

      override fun finishingInteractions() = finishingInteractions.toList().asSequence()

      override val interactionInput = interactionInput

      override fun cancelRunningInteractions(reason: String) {
        runningInteractions().forEach { it.cancel(reason) }
      }

      override fun start(
        interaction: I,
        trace: InteractionTrace,
        cancelTimeoutMillis: Long,
        onCancel: (CanceledInteractionResult<I>) -> Unit
      ): RunningInteraction<I> {
        // If the interaction input trace end isn't taken over yet, end it.
        interactionInput?.takeOverTraceEnd()?.invoke()
        val runningInteraction = RealRunningInteraction(
          startingEventSentUptime = eventSentUptime,
          interactionInput = interactionInput,
          trace = trace,
          interaction = interaction,
          cancelTimeoutMillis,
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
interface OnEventScope<I : Interaction> {
  fun runningInteractions(): Sequence<RunningInteraction<I>>
  fun finishingInteractions(): Sequence<FinishingInteraction<I>>

  val interactionInput: DeliveredInput<out InputEvent>?

  fun cancelRunningInteractions(reason: String = CANCEL_ALL_REASON_NOT_PROVIDED)

  fun start(
    interaction: I,
    trace: InteractionTrace = InteractionTrace.fromInputDelivered(interaction, interactionInput),
    cancelTimeoutMillis: Long = TimeUnit.MINUTES.toMillis(1),
    onCancel: (CanceledInteractionResult<I>) -> Unit = { }
  ): RunningInteraction<I>
}

fun interface InteractionTrace {

  fun endTrace()

  companion object {
    fun fromInputDelivered(
      interaction: Interaction,
      interactionInput: DeliveredInput<out InputEvent>?
    ): InteractionTrace {
      val endTrace = interactionInput?.takeOverTraceEnd()
      return if (endTrace != null) {
        InteractionTrace {
          endTrace()
        }
      } else {
        fromNow(interaction.description)
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

sealed interface TrackedInteraction<T : Interaction> {
  var interaction: T

  interface RunningInteraction<T : Interaction> : TrackedInteraction<T> {
    fun cancel(reason: String = CANCEL_REASON_NOT_PROVIDED)
    fun finishOnFrameRendered(block: (InteractionLatencyResult<T>) -> Unit): FinishingInteraction<T>
  }

  interface FinishingInteraction<T : Interaction> : TrackedInteraction<T>
}

class CanceledInteractionResult<T : Interaction>(
  val interaction: T,

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

class InteractionLatencyResult<T : Interaction>(
  val interaction: T,
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

object OpenCheckoutApplet : Interaction

class Navigation(val destination: Any)

class CheckoutApplet

object TapItem {
  val itemId = ""
}

object ItemVisibleInCart {
  val itemId = ""
}

class AddItemToCart(val itemId: String) : Interaction

object KeypadVisible

fun foo() {
  val client = InteractionClient<Any>()

  client.sendEvent(KeypadVisible)
  client.sendEvent(Navigation(Any()))

  val configuredRule = client.addInteraction<OpenCheckoutApplet> {
    onEvent<Navigation> { navigation ->
      if (navigation.destination is CheckoutApplet) {
        cancelRunningInteractions()
        start(OpenCheckoutApplet)
      }
    }
    onEvent<KeypadVisible> {
      runningInteractions().singleOrNull()?.finishOnFrameRendered { result ->
        logToAnalytics(result)
      }
    }
  }

  client.addInteraction<AddItemToCart> {
    onEvent<TapItem> { tapItem ->
      start(AddItemToCart(tapItem.itemId))
    }
    onEvent<ItemVisibleInCart> { itemVisibleInCart ->
      val addItemToCart = runningInteractions().firstOrNull {
        it.interaction.itemId == itemVisibleInCart.itemId
      }
      addItemToCart?.let { interaction ->
        interaction.finishOnFrameRendered { result ->
          logToAnalytics(result)
        }
      }
    }
  }

  // when we don't need this rule anymore
  configuredRule.remove()
}

fun logToAnalytics(any: Any) {
  println(any)
}