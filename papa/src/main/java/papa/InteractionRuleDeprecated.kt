package papa

import papa.InteractionResult.Finished
import papa.InteractionUpdate.CancelOnEvent
import papa.InteractionUpdate.CancelOnTimeout
import papa.InteractionUpdate.Rendered
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit.MILLISECONDS

fun interface InteractionResultListener<EventType : Any> {
  fun onInteractionResult(result: InteractionResult<EventType>)
}

@Deprecated("InteractionResultListener is replaced with InteractionUpdateListener")
fun <EventType : Any> InteractionResultListener<EventType>.asInteractionUpdateListener(): InteractionUpdateListener<EventType> {
  return InteractionUpdateListener { update ->
    when (update) {
      is CancelOnTimeout -> {
        onInteractionResult(
          InteractionResult.Canceled(
            data = InteractionResultDataPayload(
              interactionTrigger = update.interaction.interactionTrigger,
              // This has been removed.
              runningFrameCount = 0,
              sentEvents = update.interaction.sentEvents,
            ),
            cancelUptime = System.nanoTime().nanoseconds,
            cancelReason = "Timeout after ${update.timeout}"
          )
        )
      }

      is CancelOnEvent -> {
        onInteractionResult(
          InteractionResult.Canceled(
            data = InteractionResultDataPayload(
              interactionTrigger = update.interaction.interactionTrigger,
              // This has been removed.
              runningFrameCount = 0,
              sentEvents = update.interaction.sentEvents,
            ),
            cancelUptime = update.event.uptime,
            cancelReason = update.reason
          )
        )
      }

      is Rendered -> {
        onInteractionResult(
          Finished(
            data = InteractionResultDataPayload(
              interactionTrigger = update.interaction.interactionTrigger,
              // This has been removed.
              runningFrameCount = 0,
              sentEvents = update.interaction.sentEvents,
            ),
            endFrameRenderedUptime = update.frameRenderedUptime
          )
        )
      }

      else -> {}
    }
  }
}

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
      interactionTrigger?.let {
        append(
          "inputToStart=${
            (sentEvents.first().uptime - it.triggerUptime).toString(
              MILLISECONDS
            )
          }, "
        )
      }
      append("interactionInput=$interactionTrigger")
      append(")")
    }
  }
}

interface InteractionResultData<EventType : Any> {
  /**
   * Interaction input that was automatically detected when the interaction started to be tracked,
   * if any.
   */
  val interactionTrigger: InteractionTrigger?

  /**
   * The number of frames that were rendered between the first and the last event in [sentEvents]
   */
  val runningFrameCount: Int

  val sentEvents: List<SentEvent<EventType>>
}

class InteractionResultDataPayload<EventType : Any>(
  override val interactionTrigger: InteractionTrigger?,
  override val runningFrameCount: Int,
  override val sentEvents: List<SentEvent<EventType>>,
) : InteractionResultData<EventType>