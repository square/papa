package papa.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.tracing.trace

internal class GcTriggerReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent
  ) {
    trace("force gc") {
      Log.d("GcTriggerReceiver", "Triggering GC")
      gc()
      context.sendBroadcast(Intent("papa.GC_TRIGGERED"))
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
