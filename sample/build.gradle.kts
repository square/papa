plugins {
  id("com.android.application")
  kotlin("android")
}

android {
  compileSdkVersion(31)

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  defaultConfig {
    minSdkVersion(21)
    targetSdkVersion(31)
    applicationId = "com.squareup.tart.sample"
  }
}

dependencies {
  implementation(project(":tart"))
  implementation(Dependencies.AppCompat)
  implementation(Dependencies.Logcat)
}
