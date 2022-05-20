package tart.internal

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import logcat.logcat
import tart.AppState
import tart.AppState.Value.NoValue
import tart.AppState.Value.NumberValue
import tart.AppState.Value.SerializedAsync
import tart.AppState.Value.StringValue
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
import tart.UserInteractionLatencyAnalytics
import tart.UserInteractionLatencyAnalytics.TriggerData
import tart.internal.RealInputTracker.name

internal class InteractionLatencyReporterImpl : InteractionLatencyReporter {

  private val frameRenderingTracker = RealFrameRenderingTracker()
  private val inputTracker: InputTracker = InputTracker

  override fun reportImmediateInteraction(
    trigger: InteractionTrigger,
    interaction: Interaction,
    stateBeforeInteraction: AppState.Value,
    stateAfterInteraction: AppState
  ) {
    val reportStartUptimeMillis = SystemClock.uptimeMillis()
    interaction.startTrace(reportStartUptimeMillis)
    reportInteraction(
      triggerData = trigger.computeTriggerData(reportStartUptimeMillis),
      interaction = interaction,
      reportStartUptimeMillis = reportStartUptimeMillis,
      stateBeforeInteraction = stateBeforeInteraction,
      stateAfterInteraction = stateAfterInteraction,
    ) {
      interaction.endTrace(reportStartUptimeMillis)
    }
  }

  override fun <T : Interaction> startDelayedInteraction(
    trigger: InteractionTrigger,
    interaction: T,
    stateBeforeInteraction: AppState.Value
  ): Delayed<T> {
    val reportStartUptimeMillis = SystemClock.uptimeMillis()
    interaction.startTrace(reportStartUptimeMillis)

    return Delayed(interaction).apply {
      endListeners += { end ->
        when (end) {
          Cancel -> interaction.endTrace(reportStartUptimeMillis)
          is UiUpdated -> {
            reportInteraction(
              triggerData = trigger.computeTriggerData(reportStartUptimeMillis),
              interaction = interaction,
              reportStartUptimeMillis = reportStartUptimeMillis,
              stateBeforeInteraction = stateBeforeInteraction,
              stateAfterInteraction = end.stateAfterInteraction,
            ) {
              interaction.endTrace(reportStartUptimeMillis)
            }
          }
        }
      }
    }
  }

  private fun reportInteraction(
    triggerData: TriggerData,
    interaction: Interaction,
    reportStartUptimeMillis: Long,
    stateBeforeInteraction: AppState.Value,
    stateAfterInteraction: AppState,
    endTrace: () -> Unit,
  ) {
    checkMainThread()

    frameRenderingTracker.postFrameRenderedCallback {
      val rawLatencyMillis = (SystemClock.uptimeMillis() - reportStartUptimeMillis).toInt()
      endTrace()
      val stateAfterInteractionValue = when (stateAfterInteraction) {
        is AppState.Value -> stateAfterInteraction
        is ValueOnFrameRendered -> stateAfterInteraction.onFrameRendered()
      }
      val totalLatencyMillis = (triggerData.triggerDurationMillis) + rawLatencyMillis
      UserInteractionLatencyAnalytics.analytics?.reportInteraction(
        interaction = interaction,
        stateBeforeInteraction = stateBeforeInteraction,
        stateAfterInteraction = stateAfterInteractionValue,
        reportStartUptimeMillis = reportStartUptimeMillis,
        rawDurationUptimeMillis = rawLatencyMillis,
        totalDurationUptimeMillis = totalLatencyMillis,
        triggerData = triggerData,
      )
      logcat {
        val startLog = stateBeforeInteraction.asLog()
        val endLog = stateAfterInteractionValue.asLog()
        val stateLog = when {
          startLog != null && endLog != null -> {
            " (before='$startLog', after='$endLog')"
          }
          startLog != null && endLog == null -> {
            " (before='$startLog')"
          }
          startLog == null && endLog != null -> {
            " (after='$endLog')"
          }
          else -> ""
        }
        val duration =
          "$totalLatencyMillis ms: ${triggerData.triggerDurationMillis} (${triggerData.triggerName}) + $rawLatencyMillis"
        "${interaction.description} took $duration$stateLog"
      }
    }
  }

  private fun AppState.Value.asLog(): String? {
    return when (this) {
      NoValue -> null
      is NumberValue -> number.toString()
      is StringValue -> string
      // Skipping on any potentially expensive toString() call
      is SerializedAsync -> value::class.java.simpleName
    }
  }

  private fun Interaction.startTrace(reportStartUptimeMillis: Long) {
    OkTrace.beginAsyncSection {
      traceName to (reportStartUptimeMillis.rem(Int.MAX_VALUE)).toInt()
    }
  }

  private fun Interaction.endTrace(reportStartUptimeMillis: Long) {
    OkTrace.endAsyncSection {
      traceName to (reportStartUptimeMillis.rem(Int.MAX_VALUE)).toInt()
    }
  }

  private val Interaction.traceName: String
    get() = "$description Latency"

  private fun InteractionTrigger.computeTriggerData(reportStartUptimeMillis: Long) = when (this) {
    Input -> {
      checkMainThread()
      val triggerEvent = inputTracker.triggerEvent
      if (triggerEvent != null) {
        val triggerDurationMillis = (reportStartUptimeMillis - triggerEvent.event.eventTime).toInt()
        val triggerName = when (val inputEvent = triggerEvent.event) {
          is MotionEvent -> "tap"
          is KeyEvent -> {
            "key ${inputEvent.name}"
          }
          else -> error("Unexpected input event type $inputEvent")
        }
        TriggerData.Found(triggerName, triggerDurationMillis)
      } else {
        TriggerData.Unknown
      }
    }
    is Custom -> {
      val triggerDurationMillis = (reportStartUptimeMillis - triggerStartUptimeMillis).toInt()
      TriggerData.Found(name, triggerDurationMillis)
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
