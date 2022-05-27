package com.example.papa

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.metrics.performance.JankStats
import curtains.Curtains
import curtains.OnRootViewAddedListener
import curtains.phoneWindow
import curtains.windowAttachCount
import papa.AppStart
import papa.PapaEventListener
import papa.PapaEventLogger
import java.util.concurrent.Executors

class ExampleApplication : Application() {
  override fun onCreate() {
    super.onCreate()

    Handler(Looper.getMainLooper()).postDelayed({
      println("App start:\n${AppStart.latestAppStartData}")
    }, 6000)

    PapaEventListener.install(PapaEventLogger())

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
}