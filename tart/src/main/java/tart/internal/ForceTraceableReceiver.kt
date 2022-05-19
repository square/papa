package tart.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tart.OkTrace

internal class ForceTraceableReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    OkTrace.forceTraceable()
  }
}