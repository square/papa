package com.squareup.tart.sample

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.metrics.performance.JankStats
import curtains.Curtains
import curtains.OnRootViewAddedListener
import curtains.phoneWindow
import curtains.windowAttachCount
import logcat.AndroidLogcatLogger
import logcat.LogcatLogger
import logcat.logcat
import tart.LogcatTartEventListener
import tart.TartEvent.AppLaunch
import tart.TartEventListener
import tart.legacy.Perfs
import java.util.concurrent.Executors

class ExampleApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    LogcatLogger.install(AndroidLogcatLogger())

    Handler(Looper.getMainLooper()).postDelayed({
      println("App start:\n${Perfs.appStart}")
    }, 6000)

    TartEventListener.install(LogcatTartEventListener())

    TartEventListener.install { event ->
      when (event) {
        is AppLaunch -> {
          println("${event.preLaunchState.launchType} launch: ${event.durationUptimeMillis} ms")
        }
      }
    }

    Curtains.onRootViewsChangedListeners += OnRootViewAddedListener { view ->
      view.phoneWindow?.let { window ->
        if (view.windowAttachCount == 0) {
          JankStats.createAndTrack(
            window = window,
            executor = Executors.newSingleThreadExecutor()
          ) { frameData ->
            logcat { frameData.toString() }
          }
        }
      }
    }
  }
}