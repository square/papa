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
    applicationId = "com.example.papa"
  }
}

dependencies {
  implementation(project(":papa"))
  implementation(Dependencies.AppCompat)
  implementation(Dependencies.Curtains)
  implementation(Dependencies.JankStat)
}
