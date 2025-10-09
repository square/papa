import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("com.android.library")
  kotlin("android")
  id("com.vanniktech.maven.publish.base")
}

android {
  compileSdk = 36

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  resourcePrefix = "papa_"

  defaultConfig {
    minSdk = 21
    testInstrumentationRunner = "papa.test.utilities.PapaTestInstrumentationRunner"
  }
  namespace = "com.squareup.papa"
  testNamespace = "com.squareup.papa.test"

  buildFeatures {
    buildConfig = false
  }

  testOptions {
    execution = "ANDROIDX_TEST_ORCHESTRATOR"
  }
}

tasks.withType<KotlinCompile> {
  compilerOptions {
    freeCompilerArgs.set(
      listOfNotNull(
        "-opt-in=kotlin.RequiresOptIn"
      )
    )
  }
}

dependencies {

  api(project(":papa-main-trace"))
  api(project(":papa-safetrace"))
  compileOnly(Dependencies.Build.AndroidXAnnotation)

  implementation(Dependencies.Curtains)
  implementation(Dependencies.AndroidXCore)

  testImplementation(Dependencies.JUnit)
  testImplementation(Dependencies.Mockito)
  testImplementation(Dependencies.Robolectric)
  testImplementation(Dependencies.Truth)

  androidTestImplementation(Dependencies.InstrumentationTests.Core)
  androidTestImplementation(Dependencies.InstrumentationTests.Espresso)
  androidTestImplementation(Dependencies.InstrumentationTests.JUnit)
  androidTestImplementation(Dependencies.InstrumentationTests.Rules)
  androidTestImplementation(Dependencies.InstrumentationTests.Runner)
  androidTestImplementation(Dependencies.InstrumentationTests.UiAutomator)
  androidTestImplementation(Dependencies.AppCompat)
  androidTestImplementation(Dependencies.Radiography)
  // Radiography depends on a more recent version of the std lib.
  androidTestImplementation(Dependencies.Build.KotlinStbLib) {
    version {
      strictly(Versions.KotlinCompiler)
    }
  }
  androidTestImplementation(Dependencies.Truth)

  androidTestUtil(Dependencies.InstrumentationTests.Orchestrator)
}
