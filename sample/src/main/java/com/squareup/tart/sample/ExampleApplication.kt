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
import tart.AppLaunch
import tart.PreLaunchState.ACTIVITY_WAS_STOPPED
import tart.PreLaunchState.NO_ACTIVITY_BUT_SAVED_STATE
import tart.PreLaunchState.NO_ACTIVITY_NO_SAVED_STATE
import tart.PreLaunchState.NO_PROCESS
import tart.PreLaunchState.NO_PROCESS_FIRST_LAUNCH_AFTER_CLEAR_DATA
import tart.PreLaunchState.NO_PROCESS_FIRST_LAUNCH_AFTER_INSTALL
import tart.PreLaunchState.NO_PROCESS_FIRST_LAUNCH_AFTER_UPGRADE
import tart.PreLaunchState.PROCESS_WAS_LAUNCHING_IN_BACKGROUND
import tart.legacy.FrozenFrameOnTouchDetector
import tart.legacy.Perfs
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS

class ExampleApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    LogcatLogger.install(AndroidLogcatLogger())

    Handler(Looper.getMainLooper()).postDelayed({
      println("App start:\n${Perfs.appStart}")
    }, 6000)

    Perfs.appWarmStartListener = { appWarmStart ->
      println("Warm start:\n$appWarmStart")
    }

    AppLaunch.onAppLaunchListeners += { appLaunch ->
      val startType = when (appLaunch.preLaunchState) {
        NO_PROCESS -> "cold start"
        NO_PROCESS_FIRST_LAUNCH_AFTER_INSTALL -> "cold start"
        NO_PROCESS_FIRST_LAUNCH_AFTER_UPGRADE -> "cold start"
        NO_PROCESS_FIRST_LAUNCH_AFTER_CLEAR_DATA -> "cold start"
        PROCESS_WAS_LAUNCHING_IN_BACKGROUND -> "warm start"
        NO_ACTIVITY_NO_SAVED_STATE -> "warm start"
        NO_ACTIVITY_BUT_SAVED_STATE -> "warm start"
        ACTIVITY_WAS_STOPPED -> "hot start"
      }
      val durationMillis = appLaunch.duration.uptime(MILLISECONDS)
      println("$startType launch: $durationMillis ms")
    }

    FrozenFrameOnTouchDetector.install { frozenFrameOnTouch ->
      println(frozenFrameOnTouch)
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