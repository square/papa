package tart.internal

import android.content.Context
import tart.legacy.Perfs

/**
 * Inits [Perfs] on app start.
 */
internal class PerfsAppStartListener : AppStartListener() {

  override fun onAppStart(context: Context) {
    Perfs.init(context)
  }

  companion object {
    init {
      Perfs.firstClassLoaded()
    }
  }
}
