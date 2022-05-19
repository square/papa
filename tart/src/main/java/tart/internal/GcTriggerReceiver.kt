package tart.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import logcat.logcat
import tart.okTrace

internal class GcTriggerReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent
  ) {
    logcat { "Triggering GC" }
    gc()
    context.sendBroadcast(Intent("tart.GC_TRIGGERED"))
  }

  private fun gc() {
    okTrace("force gc") {
      // Code borrowed from AOSP FinalizationTest:
      // https://android.googlesource.com/platform/libcore/+/master/support/src/test/java/libcore/
      // java/lang/ref/FinalizationTester.java
      Runtime.getRuntime().gc()
      Thread.sleep(100)
      System.runFinalization()
      Thread.sleep(200)
    }
  }
}