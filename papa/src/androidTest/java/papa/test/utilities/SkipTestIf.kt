package papa.test.utilities

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Replaces Assume.assumeTrue() which doesn't seem to work well with the Android test
 * runner ("failed: Test run failed to complete. Expected 18 tests, received 16")
 */
class SkipTestIf(private val skipPredicate: () -> Boolean) : TestRule {
  override fun apply(
    base: Statement,
    description: Description
  ): Statement {
    return object : Statement() {
      override fun evaluate() {
        if (!skipPredicate()) {
          base.evaluate()
        }
      }
    }
  }
}
