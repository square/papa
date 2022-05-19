package tart.internal

import android.app.Application

/**
 * Automatically set on app start by [tart.legacy.Perfs]
 */
internal object ApplicationHolder {
  var application: Application? = null
}