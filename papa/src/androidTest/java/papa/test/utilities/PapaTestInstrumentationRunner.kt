package papa.test.utilities

import android.os.Bundle
import androidx.test.espresso.Espresso
import androidx.test.espresso.base.DefaultFailureHandler
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import androidx.test.uiautomator.UiDevice
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import radiography.Radiography
import radiography.ViewStateRenderers.DefaultsIncludingPii
import java.io.ByteArrayOutputStream
import java.io.StringReader

class PapaTestInstrumentationRunner : AndroidJUnitRunner() {

  override fun onCreate(arguments: Bundle?) {
    super.onCreate(arguments)
    val defaultFailureHandler =
      DefaultFailureHandler(InstrumentationRegistry.getInstrumentation().targetContext)
    Espresso.setFailureHandler { error, viewMatcher ->
      try {
        defaultFailureHandler.handle(error, viewMatcher)
      } catch (decoratedError: Throwable) {
        val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val os = ByteArrayOutputStream()
        uiDevice.dumpWindowHierarchy(os)
        val uiAutomatorWindowHierarchy = os.toString()
        val factory = XmlPullParserFactory.newInstance()
        val xpp = factory.newPullParser()
        xpp.setInput(StringReader(uiAutomatorWindowHierarchy))
        val result = StringBuilder()

        var eventType = xpp.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
          if (eventType == XmlPullParser.START_TAG) {
            when (xpp.name) {
              "hierarchy" -> {
                result.appendLine(
                  "view hierarchy with screen rotation ${xpp.getAttributeValue(null, "rotation")}"
                )
              }
              "node" -> {
                val interestingAttributes = listOf(
                  "text", "resource-id", "checked", "enabled", "focused", "selected", "bounds",
                  "visible-to-user", "package"
                )
                val className = xpp.getAttributeValue(null, "class").substringAfterLast(".")
                val attributes = (0 until xpp.attributeCount)
                  .asSequence()
                  .mapNotNull { index ->
                    val name = xpp.getAttributeName(index)
                    val value = xpp.getAttributeValue(index)
                    if (value.isNullOrBlank() || name !in interestingAttributes) {
                      return@mapNotNull null
                    }
                    when (name) {
                      "checked" -> if (value == "true") "checked" else null
                      "focused" -> if (value == "true") "focused" else null
                      "selected" -> if (value == "true") "selected" else null
                      "enabled" -> if (value == "true") null else "disabled"
                      "visible-to-user" -> if (value == "true") null else "invisible"
                      "text" -> "text:\"$value\""
                      "package" -> {
                        // Root view nodes have depth 2 (depth starts at 1 with the "hierarchy" node)
                        if (xpp.depth == 2) {
                          "app-package:$value"
                        } else {
                          null
                        }
                      }
                      "resource-id" -> "id:${value.substringAfter(":id/")}"
                      else -> "$name:$value"
                    }
                  }.toList().joinToString(separator = ", ")
                result.append("│")
                  .append("  ".repeat(xpp.depth - 2))
                  .appendLine("$className { $attributes }")
              }
              else -> error("Unexpected tag ${xpp.name}")
            }
          }
          eventType = xpp.next()
        }
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
          message += "\nUI Automator window hierarchy:\n$result"
          detailMessageField[decoratedError] = message
        } finally {
          detailMessageField.isAccessible = previouslyAccessible
        }
        throw decoratedError
      }
    }
  }
}
