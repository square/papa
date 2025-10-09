package com.example.papa

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import androidx.metrics.performance.JankStats
import curtains.Curtains
import curtains.OnKeyEventListener
import curtains.OnRootViewAddedListener
import curtains.OnTouchEventListener
import curtains.keyEventInterceptors
import curtains.phoneWindow
import curtains.touchEventInterceptors
import curtains.windowAttachCount
import papa.AppStart
import papa.EventFrameLabeler
import papa.InteractionEventSink
import papa.InteractionRuleClient
import papa.PapaEventListener
import papa.PapaEventLogger
import papa.SafeTraceSetup
import papa.SectionNameMapper
import papa.WindowOverlay
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

class ExampleApplication : Application() {

  private val interactionRuleClient = InteractionRuleClient<InteractionEvent> { update ->
    Log.d("ExampleApplication", "$update")
  }

  private lateinit var interactionOverlay: WindowOverlay

  override fun onCreate() {
    super.onCreate()

    Handler(Looper.getMainLooper()).postDelayed({
      println("App start:\n${AppStart.latestAppStartData}")
    }, 6000)

    PapaEventListener.install(PapaEventLogger())

    interactionRuleClient.apply {
      addInteractionRule {
        onEvent<OnMainActivityButtonClick> {
          startInteraction().finish()
        }
      }
      addInteractionRule {
        onEvent<OnMainActivityButtonClick> {
          startInteraction(cancelTimeout = 2000.milliseconds)
        }
      }
      addInteractionRule {
        onEvent<OnTouchLagClick> {
          startInteraction().finish()
        }
      }
    }

    val labelView = TextView(this@ExampleApplication)
    labelView.textSize = 20f
    labelView.setTextColor(Color.rgb(255, 0, 0))
    labelView.text = "Initial text"

    val frameLabeler = EventFrameLabeler()

    SafeTraceSetup.mainThreadSectionNameMapper = SectionNameMapper { name ->
      val mappedName = frameLabeler.mapSectionNameIfFrame(name)
      labelView.text = mappedName
      mappedName ?: SafeTraceSetup.cleanUpMainThreadSectionName(name)
    }
    interactionOverlay = WindowOverlay(this) { labelView }

    Curtains.onRootViewsChangedListeners += OnRootViewAddedListener { view ->
      view.phoneWindow?.let { window ->
        if (view.windowAttachCount == 0) {
          window.touchEventInterceptors += OnTouchEventListener {
            frameLabeler.onEvent(MotionEvent.actionToString(it.action))
          }
          window.keyEventInterceptors += OnKeyEventListener {
            frameLabeler.onEvent(KeyEvent.keyCodeToString(it.keyCode))
          }
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
    val Context.interactionEventSink: InteractionEventSink<InteractionEvent>
      get() {
        val app = applicationContext as ExampleApplication
        return app.interactionRuleClient
      }

    val Context.interactionOverlay: WindowOverlay
      get() {
        val app = applicationContext as ExampleApplication
        return app.interactionOverlay
      }
  }
}
