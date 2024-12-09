plugins {
  id("com.android.application")
  kotlin("android")
}

android {
  compileSdk = 31

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  defaultConfig {
    minSdk = 21
    targetSdk = 31
    applicationId = "com.example.papa"
  }
  namespace = "com.example.papa"
}

dependencies {
  implementation(project(":papa"))
  implementation(project(":papa-dev"))
  implementation(Dependencies.AppCompat)
  implementation(Dependencies.Curtains)
  implementation(Dependencies.JankStat)
  implementation(Dependencies.Material)
}
