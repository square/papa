package com.example.papa

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.metrics.performance.JankStats
import curtains.Curtains
import curtains.OnRootViewAddedListener
import curtains.phoneWindow
import curtains.windowAttachCount
import papa.AppStart
import papa.InteractionEventReceiver
import papa.InteractionLatencyResult
import papa.InteractionRuleClient
import papa.PapaEventListener
import papa.PapaEventLogger
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

class ExampleApplication : Application() {

  private val logInteractionResult: (InteractionLatencyResult<out UiInteraction>) -> Unit =
    { result ->
      Log.d("ExampleApplication", "${result.interaction.name}: $result")
    }

  private val interactionRuleClient = InteractionRuleClient<InteractionEvent, UiInteraction>()

  override fun onCreate() {
    super.onCreate()

    Handler(Looper.getMainLooper()).postDelayed({
      println("App start:\n${AppStart.latestAppStartData}")
    }, 6000)

    PapaEventListener.install(PapaEventLogger())

    interactionRuleClient.apply {
      addInteractionRule<UpdateTextInteraction> {
        onEvent<OnMainActivityButtonClick> { event ->
          startInteraction(UpdateTextInteraction(event)).finishOnFrameRendered(logInteractionResult)
        }
      }
      addInteractionRule<NeverFinishedInteraction> {
        onEvent<OnMainActivityButtonClick> {
          startInteraction(NeverFinishedInteraction, onCancel = {
            Log.d("ExampleApplication", "canceled: $it")
          }, cancelTimeout = 2000.milliseconds)
        }
      }
      addInteractionRule<TouchLagInteraction> {
        onEvent<OnTouchLagClick> {
          startInteraction(TouchLagInteraction).finishOnFrameRendered(logInteractionResult)
        }
      }
    }

    // Uncomment to try out jankstat
    if (false) {
      Curtains.onRootViewsChangedListeners += OnRootViewAddedListener { view ->
        view.phoneWindow?.let { window ->
          if (view.windowAttachCount == 0) {
            JankStats.createAndTrack(
              window = window,
              executor = Executors.newSingleThreadExecutor()
            ) { frameData ->
              Log.d("JankStats", frameData.toString())
            }
          }
        }
      }
    }
  }

  companion object {
    val Context.interactionEventReceiver: InteractionEventReceiver<InteractionEvent>
      get() {
        val app = applicationContext as ExampleApplication
        return app.interactionRuleClient
      }
  }
}