package tart.internal

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
import tart.CpuDuration
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
import tart.expectedFrameDurationNanos
import tart.internal.RealInputTracker.name
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.NANOSECONDS
import kotlin.math.ceil

internal class InteractionLatencyReporterImpl : InteractionLatencyReporter {

  private val latencyTraceEndHandler by lazy {
    Handler(HandlerThread("Latency Trace End Thread").apply { start() }.looper)
  }

  private val inputTracker: InputTracker = InputTracker

  override fun reportImmediateInteraction(
    trigger: InteractionTrigger,
    interaction: Interaction,
    stateBeforeInteraction: AppState.Value,
    stateAfterInteraction: AppState
  ) {
    val reportStart = CpuDuration.now()
    val reportStartUptimeMillis = reportStart.uptime(MILLISECONDS)
    interaction.startTrace(reportStartUptimeMillis)
    reportInteraction(
      triggerData = trigger.computeTriggerData(reportStart),
      interaction = interaction,
      reportStart = reportStart,
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
    val reportStart = CpuDuration.now()
    val reportStartUptimeMillis = reportStart.uptime(MILLISECONDS)
    interaction.startTrace(reportStartUptimeMillis)

    return Delayed(interaction).apply {
      endListeners += { end ->
        when (end) {
          Cancel -> interaction.endTrace(reportStartUptimeMillis)
          is UiUpdated -> {
            reportInteraction(
              triggerData = trigger.computeTriggerData(reportStart),
              interaction = interaction,
              reportStart = reportStart,
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
    reportStart: CpuDuration,
    stateBeforeInteraction: AppState.Value,
    stateAfterInteraction: AppState,
    endTrace: () -> Unit,
  ) {
    checkMainThread()

    // The frame callback runs somewhat in the middle of rendering, so by posting at the front
    // of the queue from there we get the timestamp for right when the next frame is done
    // rendering.
    Choreographer.getInstance().postFrameCallback { frameStartUptimeNanos ->
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
        val frameDone = CpuDuration.now()

        val frameDurationNanos = frameDone.uptime(NANOSECONDS) - frameStartUptimeNanos

        val expectedFrameDurationNanos = expectedFrameDurationNanos

        // Round up to the closest next vsync
        // Note: this can be off in 2 ways:
        // 1) This main thread message runs after the display has synced, which means we can be off
        // by one frame too late.
        // 2) The remaining time to display the next frame wasn't large enough to get the frame
        // through the display sub system which means we're off by one frame too early.
        val frameDisplayedUptimeNanos =
          frameStartUptimeNanos + ceil(frameDurationNanos.toDouble() / expectedFrameDurationNanos).toLong() * expectedFrameDurationNanos

        val frameDisplayed =
          CpuDuration.fromUptime(NANOSECONDS, frameDisplayedUptimeNanos)

        val durationFromStart = frameDisplayed - reportStart
        val totalDuration = triggerData.triggerDuration + durationFromStart

        logcat {
          """
          Frame started at ${CpuDuration.fromUptime(NANOSECONDS, frameStartUptimeNanos)}
          Frame done at $frameDone
          Frame displayed at $frameDisplayed
          Latency duration: $durationFromStart
        """.trimIndent()
        }

        if (OkTrace.isCurrentlyTracing) {
          // Make sure the trace finishes at the same reported end time.
          latencyTraceEndHandler.postAtTime({ endTrace() }, frameDisplayed.uptime(MILLISECONDS))
        }

        val stateAfterInteractionValue = when (stateAfterInteraction) {
          is AppState.Value -> stateAfterInteraction
          is ValueOnFrameRendered -> stateAfterInteraction.onFrameRendered()
        }
        UserInteractionLatencyAnalytics.analytics?.reportInteraction(
          interaction = interaction,
          stateBeforeInteraction = stateBeforeInteraction,
          stateAfterInteraction = stateAfterInteractionValue,
          reportStart = reportStart,
          durationFromStart = durationFromStart,
          totalDuration = totalDuration,
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
            "${totalDuration.uptime(MILLISECONDS)} ms: ${
              triggerData.triggerDuration.uptime(MILLISECONDS)
            } (${triggerData.triggerName}) + ${durationFromStart.uptime(MILLISECONDS)}"
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

  private fun InteractionTrigger.computeTriggerData(reportStart: CpuDuration) = when (this) {
    Input -> {
      checkMainThread()
      val triggerEvent = inputTracker.triggerEvent
      if (triggerEvent != null) {
        val eventTime = CpuDuration.fromUptime(MILLISECONDS, triggerEvent.event.eventTime)
        val triggerDuration = reportStart - eventTime
        val triggerName = when (val inputEvent = triggerEvent.event) {
          is MotionEvent -> "tap"
          is KeyEvent -> {
            "key ${inputEvent.name}"
          }
          else -> error("Unexpected input event type $inputEvent")
        }
        TriggerData.Found(triggerName, triggerDuration)
      } else {
        TriggerData.Unknown
      }
    }
    is Custom -> {
      val triggerStart = CpuDuration.fromUptime(MILLISECONDS, triggerStartUptimeMillis)
      val triggerDuration = reportStart - triggerStart
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
