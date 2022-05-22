package tart

import android.util.Log

class LogcatTartEventListener : TartEventListener {

  override fun onEvent(event: TartEvent) {
    Log.d("LogcatTartEventListener", event.toString())
  }
}