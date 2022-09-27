package papa.internal

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import papa.AppState
import papa.AppState.ValueOnFrameRendered
import papa.InputTracker
import papa.Interaction
import papa.Interaction.Delayed
import papa.Interaction.Delayed.End.Cancel
import papa.Interaction.Delayed.End.UiUpdated
import papa.InteractionLatencyReporter
import papa.InteractionTrigger
import papa.InteractionTrigger.Custom
import papa.InteractionTrigger.InputEvent
import papa.InteractionTrigger.Unknown
import papa.SafeTrace
import papa.PapaEvent.InteractionLatency
import papa.PapaEventListener
import papa.TriggerData
import papa.internal.RealInputTracker.name
import java.util.concurrent.TimeUnit

internal class InteractionLatencyReporterImpl : InteractionLatencyReporter {

  private val inputTracker: InputTracker = InputTracker

  override fun reportImmediateInteraction(
    trigger: InteractionTrigger,
    interaction: Interaction,
    stateBeforeInteraction: AppState.Value,
    stateAfterInteraction: AppState
  ) {
    val startUptimeMillis = SystemClock.uptimeMillis()
    interaction.startTrace(startUptimeMillis)
    reportInteraction(
      triggerData = trigger.computeTriggerData(startUptimeMillis),
      interaction = interaction,
      startUptimeMillis = startUptimeMillis,
      stateBeforeInteraction = stateBeforeInteraction,
      stateAfterInteraction = stateAfterInteraction,
    ) {
      interaction.endTrace(startUptimeMillis)
    }
  }

  override fun <T : Interaction> startDelayedInteraction(
    trigger: InteractionTrigger,
    interaction: T,
    stateBeforeInteraction: AppState.Value
  ): Delayed<T> {
    val startUptimeMillis = SystemClock.uptimeMillis()
    interaction.startTrace(startUptimeMillis)

    return Delayed(interaction).apply {
      endListeners += { end ->
        when (end) {
          Cancel -> interaction.endTrace(startUptimeMillis)
          is UiUpdated -> {
            reportInteraction(
              triggerData = trigger.computeTriggerData(startUptimeMillis),
              interaction = interaction,
              startUptimeMillis = startUptimeMillis,
              stateBeforeInteraction = stateBeforeInteraction,
              stateAfterInteraction = end.stateAfterInteraction,
            ) {
              interaction.endTrace(startUptimeMillis)
            }
          }
        }
      }
    }
  }

  private fun reportInteraction(
    triggerData: TriggerData,
    interaction: Interaction,
    startUptimeMillis: Long,
    stateBeforeInteraction: AppState.Value,
    stateAfterInteraction: AppState,
    endTrace: () -> Unit,
  ) {
    checkMainThread()

    onCurrentOrNextFrameRendered { frameRenderedUptime ->
        endTrace()
        val durationFromStartUptimeMillis = frameRenderedUptime.inWholeMilliseconds - startUptimeMillis

        val stateAfterInteractionValue = when (stateAfterInteraction) {
          is AppState.Value -> stateAfterInteraction
          is ValueOnFrameRendered -> stateAfterInteraction.onFrameRendered()
        }

        PapaEventListener.sendEvent(
          InteractionLatency(
            interaction = interaction,
            stateBeforeInteraction = stateBeforeInteraction,
            stateAfterInteraction = stateAfterInteractionValue,
            startUptimeMillis = startUptimeMillis,
            displayDurationUptimeMillis = durationFromStartUptimeMillis,
            triggerData = triggerData,
          )
        )
    }
  }

  private fun Interaction.startTrace(startUptimeMillis: Long) {
    SafeTrace.beginAsyncSection {
      traceName to (startUptimeMillis.rem(Int.MAX_VALUE)).toInt()
    }
  }

  private fun Interaction.endTrace(startUptimeMillis: Long) {
    SafeTrace.endAsyncSection {
      traceName to (startUptimeMillis.rem(Int.MAX_VALUE)).toInt()
    }
  }

  private val Interaction.traceName: String
    get() = "$description Latency"

  private fun InteractionTrigger.computeTriggerData(startUptimeMillis: Long) = when (this) {
    InputEvent -> {
      checkMainThread()
      val triggerEvent = inputTracker.triggerEvent
      if (triggerEvent != null) {
        val triggerDurationUptimeMillis = startUptimeMillis - triggerEvent.event.eventTime
        val triggerName = when (val inputEvent = triggerEvent.event) {
          is MotionEvent -> "tap"
          is KeyEvent -> {
            "key ${inputEvent.name}"
          }
          else -> error("Unexpected input event type $inputEvent")
        }
        TriggerData.Found(triggerName, triggerDurationUptimeMillis)
      } else {
        TriggerData.Unknown
      }
    }
    is Custom -> {
      val triggerDuration = startUptimeMillis - triggerStartUptimeMillis
      TriggerData.Found(name, triggerDuration)
    }
    Unknown -> TriggerData.Unknown
  }

  companion object {

    private val isMainThread: Boolean get() = Looper.getMainLooper().thread === Thread.currentThread()

    private fun checkMainThread() {
      check(isMainThread) {
        "Should be called from the main thread, not ${Thread.currentThread()}"
      }
    }
  }
}
