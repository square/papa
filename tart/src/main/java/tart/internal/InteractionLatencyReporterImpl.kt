package tart.internal

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import tart.AppState
import tart.AppState.ValueOnFrameRendered
import tart.InputTracker
import tart.Interaction
import tart.Interaction.Delayed
import tart.Interaction.Delayed.End.Cancel
import tart.Interaction.Delayed.End.UiUpdated
import tart.InteractionLatencyReporter
import tart.InteractionTrigger
import tart.InteractionTrigger.Custom
import tart.InteractionTrigger.Input
import tart.InteractionTrigger.Unknown
import tart.OkTrace
import tart.TartEvent.InteractionLatency
import tart.TartEventListener
import tart.TriggerData
import tart.internal.RealInputTracker.name

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

    onCurrentOrNextFrameRendered { frameRenderedUptimeMillis ->
        endTrace()
        val durationFromStartUptimeMillis = frameRenderedUptimeMillis - startUptimeMillis

        val stateAfterInteractionValue = when (stateAfterInteraction) {
          is AppState.Value -> stateAfterInteraction
          is ValueOnFrameRendered -> stateAfterInteraction.onFrameRendered()
        }

        TartEventListener.sendEvent(
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
    OkTrace.beginAsyncSection {
      traceName to (startUptimeMillis.rem(Int.MAX_VALUE)).toInt()
    }
  }

  private fun Interaction.endTrace(startUptimeMillis: Long) {
    OkTrace.endAsyncSection {
      traceName to (startUptimeMillis.rem(Int.MAX_VALUE)).toInt()
    }
  }

  private val Interaction.traceName: String
    get() = "$description Latency"

  private fun InteractionTrigger.computeTriggerData(startUptimeMillis: Long) = when (this) {
    Input -> {
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
