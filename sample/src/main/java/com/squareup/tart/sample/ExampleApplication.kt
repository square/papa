package com.squareup.tart.sample

import android.app.Application
import android.os.Handler
import android.os.Looper
import tart.legacy.Perfs

class ExampleApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    Handler(Looper.getMainLooper()).postDelayed({
      println("App start:\n${Perfs.appStart}")
    }, 6000)

    Perfs.appWarmStartListener = { appWarmStart ->
      println("Warm start:\n$appWarmStart")
    }
  }
}