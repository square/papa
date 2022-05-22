package tart.internal

import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
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

    // The frame callback runs somewhat in the middle of rendering, so by posting at the front
    // of the queue from there we get the timestamp for right when the next frame is done
    // rendering.
    Choreographer.getInstance().postFrameCallback {
      // The main thread is a single thread, and work always executes one task at a time. We're
      // creating an async message that won't be reordered to execute after sync barriers (which are
      // used for processing input events and rendering frames). "sync barriers" are essentially
      // prioritized messages. When you post, you
      // can post to the front or the back, and you're just inserting in a different place in the
      // queue. When the queue is done with the current message, it looks at its head for the next
      // message. Unless there's a special message enqueued called a sync barrier, which basically has
      // top priority and will run prior to the current head if its time that's gone. Async prevents
      // this behavior.
      mainHandler.postAtFrontOfQueueAsync {
        val frameDoneUptimeMillis = SystemClock.uptimeMillis()
        endTrace()

        val durationFromStartUptimeMillis = frameDoneUptimeMillis - startUptimeMillis

        val stateAfterInteractionValue = when (stateAfterInteraction) {
          is AppState.Value -> stateAfterInteraction
          is ValueOnFrameRendered -> stateAfterInteraction.onFrameRendered()
        }
        UserInteractionLatencyAnalytics.analytics?.reportInteraction(
          interaction = interaction,
          stateBeforeInteraction = stateBeforeInteraction,
          stateAfterInteraction = stateAfterInteractionValue,
          startUptimeMillis = startUptimeMillis,
          durationFromStartUptimeMillis = durationFromStartUptimeMillis,
          triggerData = triggerData,
        )
        logcat {
          val totalDurationUptimeMillis =
            durationFromStartUptimeMillis + triggerData.triggerDurationUptimeMillis
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
            "$totalDurationUptimeMillis ms: ${
              triggerData.triggerDurationUptimeMillis
            } (${triggerData.triggerName}) + $durationFromStartUptimeMillis"
          "${interaction.description} took $duration$stateLog"
        }

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
