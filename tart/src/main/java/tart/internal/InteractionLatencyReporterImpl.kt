package tart

import android.os.Looper
import android.os.SystemClock
import logcat.logcat
import tart.AppState.Value.NoValue
import tart.AppState.Value.NumberValue
import tart.AppState.Value.SerializedAsync
import tart.AppState.Value.StringValue
import tart.AppState.ValueOnFrameRendered
import tart.Interaction.Delayed
import tart.Interaction.Delayed.End.Cancel
import tart.Interaction.Delayed.End.UiUpdated
import tart.InteractionTrigger.Custom
import tart.InteractionTrigger.Input
import tart.InteractionTrigger.Unknown
import tart.internal.RealFrameRenderingTracker
import tart.legacy.TouchMetrics

internal class InteractionLatencyReporterImpl : InteractionLatencyReporter {

  private val frameRenderingTracker = RealFrameRenderingTracker()
  private val touchMetrics: TouchMetrics = TouchMetrics

  // TODO Provide way to install analytics
  private var analytics: UserInteractionLatencyAnalytics? = null

  class TriggerData(
    val triggerDurationMillisOrNull: Int?,
    val triggerName: String
  ) {
    companion object {
      val UNKNOWN = TriggerData(null, "unknown")
    }
  }

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
      val totalLatencyMillis = (triggerData.triggerDurationMillisOrNull ?: 0) + rawLatencyMillis
      analytics?.reportInteraction(
        description = interaction.description,
        stateBeforeInteraction = stateBeforeInteraction,
        stateAfterInteraction = stateAfterInteractionValue,
        reportStartUptimeMillis = reportStartUptimeMillis,
        rawDurationUptimeMillis = rawLatencyMillis,
        totalDurationUptimeMillis = totalLatencyMillis,
        triggerDurationUptimeMillis = triggerData.triggerDurationMillisOrNull,
        triggerName = triggerData.triggerName,
      )
      logcat {
        val startLog = stateBeforeInteraction.asLog()
        val endLog = stateAfterInteractionValue.asLog()
        val stateLog = when {
          startLog != null && endLog != null -> {
            "(before='$startLog', after='$endLog')"
          }
          startLog != null && endLog == null -> {
            "(before='$startLog')"
          }
          startLog == null && endLog != null -> {
            "(after='$endLog')"
          }
          else -> ""
        }
        val duration =
          "$totalLatencyMillis ms (${triggerData.triggerDurationMillisOrNull} + $rawLatencyMillis)"
        "${interaction.description}$stateLog took $duration"
      }
    }
  }

  private fun AppState.Value.asLog(): String? {
    return when (this) {
      NoValue -> null
      is NumberValue -> this.number.toString()
      is StringValue -> this.string
      // Skipping on any potentially expensive toString() call
      is SerializedAsync -> null
    }
  }

  internal fun installAnalytics(userInteractionLatencyAnalytics: UserInteractionLatencyAnalytics) {
    analytics = userInteractionLatencyAnalytics
  }

  fun uninstallAnalytics() {
    analytics = null
  }

  private fun Interaction.startTrace(reportStartUptimeMillis: Long) {
    // TODO OkTrace should support computing this data async with blocks
    // Skip computation if not necessary
    if (!OkTrace.isTraceable) {
      return
    }
    OkTrace.beginAsyncSection(traceName, (reportStartUptimeMillis.rem(Int.MAX_VALUE)).toInt())
  }

  private fun Interaction.endTrace(reportStartUptimeMillis: Long) {
    // Skip computation if not necessary
    if (!OkTrace.isTraceable) {
      return
    }
    OkTrace.endAsyncSection(traceName, (reportStartUptimeMillis.rem(Int.MAX_VALUE)).toInt())
  }

  private val Interaction.traceName: String
    get() = "$description Latency"

  private fun InteractionTrigger.computeTriggerData(reportStartUptimeMillis: Long) = when (this) {
    Input -> {
      checkMainThread()
      // Touch up and back key events are mutually exclusive- the event times fields are only set
      // if the user interaction is underway, i.e. this is called as a result of onBackPressed()
      // or onClicked()
      touchMetrics.lastTouchUpEvent?.let { (motionEvent, _) ->
        TriggerData((reportStartUptimeMillis - motionEvent.eventTime).toInt(), "tap")
      } ?: touchMetrics.lastBackKeyEvent?.let { (backKeyEventTime, _) ->
        TriggerData((reportStartUptimeMillis - backKeyEventTime).toInt(), "back")
      } ?: TriggerData.UNKNOWN
    }
    is Custom -> {
      TriggerData((reportStartUptimeMillis - triggerStartUptimeMillis).toInt(), name)
    }
    Unknown -> TriggerData.UNKNOWN
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
