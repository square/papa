package papa.test.utilities

import android.os.Bundle
import androidx.test.espresso.Espresso
import androidx.test.espresso.base.DefaultFailureHandler
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import radiography.Radiography
import radiography.ViewStateRenderers.DefaultsIncludingPii

class PapaTestInstrumentationRunner : AndroidJUnitRunner() {

  override fun onCreate(arguments: Bundle?) {
    super.onCreate(arguments)
    val defaultFailureHandler =
      DefaultFailureHandler(InstrumentationRegistry.getInstrumentation().targetContext)
    Espresso.setFailureHandler { error, viewMatcher ->
      try {
        defaultFailureHandler.handle(error, viewMatcher)
      } catch (decoratedError: Throwable) {
        val detailMessageField = Throwable::class.java.getDeclaredField("detailMessage")
        val previouslyAccessible = detailMessageField.isAccessible
        try {
          detailMessageField.isAccessible = true
          var message = (detailMessageField[decoratedError] as String?).orEmpty()

          // Remove Espresso terrible view hierarchy rendering.
          message = message.substringBefore("\nView Hierarchy:")

          val hierarchy = Radiography.scan(viewStateRenderers = DefaultsIncludingPii)
          // Notice the plural: there's one view hierarchy per window.
          message += "\nView hierarchies:\n$hierarchy"
          detailMessageField[decoratedError] = message
        } finally {
          detailMessageField.isAccessible = previouslyAccessible
        }
        throw decoratedError
      }
    }
  }
}
