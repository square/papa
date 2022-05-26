package papa

import papa.AppState.Value.NoValue
import papa.Interaction.Delayed
import papa.internal.InteractionLatencyReporterImpl

/**
 * Reports the latency of user interactions.
 */
interface InteractionLatencyReporter {

  /**
   * Reports a user interaction that immediately updated the UI.
   *
   * The report will timestamp the next frame rendered after this has been called to serve as the
   * official end time, and then the interaction will be enqueued with analytics.
   *
   * [stateBeforeInteraction]: Loggable metadata related to the app state before the interaction.
   * [stateAfterInteraction]: Loggable metadata related to the app state after the interaction.
   *
   * Must be called from the main thread.
   */
  fun reportImmediateInteraction(
    trigger: InteractionTrigger,
    interaction: Interaction,
    stateBeforeInteraction: AppState.Value = NoValue,
    stateAfterInteraction: AppState = NoValue
  )

  /**
   * Starts the report of a user interaction that will eventually update the UI.
   *
   * After calling this method there should always be a symmetric call to either [Delayed.cancel] or
   * [Delayed.reportUiUpdated].
   *
   * If the returned [Delayed] is garbage collected without having called [Delayed.cancel] or
   * [Delayed.reportUiUpdated], [Delayed.cancel] will be automatically called.
   *
   * If [trigger] is [InteractionTrigger.Input] then must be called from the main thread, otherwise
   * can be called from any thread.
   *
   * [stateBeforeInteraction]: Loggable metadata related to the app state before the interaction.
   */
  fun <T : Interaction> startDelayedInteraction(
    trigger: InteractionTrigger,
    interaction: T,
    stateBeforeInteraction: AppState.Value = NoValue
  ): Delayed<T>

  companion object : InteractionLatencyReporter by InteractionLatencyReporterImpl()
}
