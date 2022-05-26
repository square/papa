package papa.internal

import android.content.Context

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
