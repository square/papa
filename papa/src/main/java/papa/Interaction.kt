package papa

import papa.AppState.Value.NoValue
import papa.Interaction.Delayed.End.Cancel
import papa.Interaction.Delayed.End.UiUpdated
import java.util.Collections

interface Interaction {

  /**
   * A description for the user interaction that will be used in logging, analytics and traces.
   * Defaults to the simple class name of the [Interaction] implementation.
   */
  val description: String
    get() = this::class.java.simpleName

  /**
   * An interaction that did not immediately update the UI with the result of the
   * user action.
   */
  class Delayed<T : Interaction>(val interaction: T?) {

    sealed class End {
      object Cancel : End()
      class UiUpdated(val stateAfterInteraction: AppState) : End()
    }

    val endListeners: MutableList<(End) -> Unit> = Collections.synchronizedList(mutableListOf())

    /**
     * Can be called from any thread.
     */
    fun cancel() {
      for (listener in endListeners) {
        listener(Cancel)
      }
    }

    /**
     * Reports the user interaction is done updating the UI.
     *
     * The report will timestamp the next frame rendered after this has been called to serve as the
     * official end time, and then the interaction will be enqueued with analytics.
     *
     * [stateAfterInteraction]: Loggable metadata related to the app state after the interaction.
     *
     * Must be called from the main thread.
     */
    fun reportUiUpdated(stateAfterInteraction: AppState = NoValue) {
      val uiUpdated = UiUpdated(stateAfterInteraction)
      for (listener in endListeners) {
        listener(uiUpdated)
      }
    }
  }
}
