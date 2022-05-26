package tart.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import tart.safeTrace

internal class GcTriggerReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent
  ) {
    safeTrace("force gc") {
      Log.d("GcTriggerReceiver", "Triggering GC")
      gc()
      context.sendBroadcast(Intent("tart.GC_TRIGGERED"))
    }
  }

  private fun gc() {
    // Code borrowed from AOSP FinalizationTest:
    // https://android.googlesource.com/platform/libcore/+/master/support/src/test/java/libcore/
    // java/lang/ref/FinalizationTester.java
    Runtime.getRuntime().gc()
    Thread.sleep(100)
    System.runFinalization()
    Thread.sleep(200)
  }
}