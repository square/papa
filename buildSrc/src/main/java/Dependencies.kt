object Versions {
  /**
   * To change this in the IDE, use `systemProp.square.kotlinVersion=x.y.z` in your
   * `~/.gradle/gradle.properties` file.
   */
  val KotlinCompiler = System.getProperty("square.kotlinVersion") ?: "1.9.10"

  const val AndroidXTest = "1.5.0"
}

object Dependencies {
  object Build {
    const val Android = "com.android.tools.build:gradle:8.1.2"
    const val MavenPublish = "com.vanniktech:gradle-maven-publish-plugin:0.25.3"
    val Kotlin = "org.jetbrains.kotlin:kotlin-gradle-plugin:${Versions.KotlinCompiler}"
    const val Ktlint = "org.jlleitschuh.gradle:ktlint-gradle:11.6.1"
    const val AndroidXAnnotation = "androidx.annotation:annotation:1.1.0"
    const val BinaryCompatibility = "org.jetbrains.kotlinx:binary-compatibility-validator:0.16.3"
    val KotlinStbLib = "org.jetbrains.kotlin:kotlin-stdlib-jdk8:${Versions.KotlinCompiler}"
  }

  const val AppCompat = "androidx.appcompat:appcompat:1.2.0"
  const val Curtains = "com.squareup.curtains:curtains:1.2.5"
  const val JUnit = "junit:junit:4.13"
  const val Mockito = "org.mockito:mockito-core:3.4.6"
  const val Robolectric = "org.robolectric:robolectric:4.10.1"
  const val Truth = "com.google.truth:truth:1.0.1"
  const val AndroidXCore = "androidx.core:core:1.6.0"
  const val AndroidXTracing = "androidx.tracing:tracing-ktx:1.1.0"
  const val Radiography = "com.squareup.radiography:radiography:2.7"
  const val JankStat = "androidx.metrics:metrics-performance:1.0.0-alpha01"
  const val Material = "com.google.android.material:material:1.6.0"

  object InstrumentationTests {
    const val Core = "androidx.test:core:${Versions.AndroidXTest}"
    const val Espresso = "androidx.test.espresso:espresso-core:3.5.1"
    const val JUnit = "androidx.test.ext:junit:1.1.5"
    const val Orchestrator = "androidx.test:orchestrator:${Versions.AndroidXTest}"
    const val Rules = "androidx.test:rules:${Versions.AndroidXTest}"
    const val Runner = "androidx.test:runner:${Versions.AndroidXTest}"
    const val UiAutomator = "androidx.test.uiautomator:uiautomator:2.2.0"
  }
}
