import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("com.android.library")
  kotlin("android")
  id("com.vanniktech.maven.publish")
}

android {
  compileSdkVersion(31)

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  resourcePrefix = "papa_"

  defaultConfig {
    minSdkVersion(21)
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures {
    buildConfig = false
  }
}

tasks.withType<KotlinCompile> {
  kotlinOptions {
    freeCompilerArgs = listOfNotNull(
      // allow-jvm-ir-dependencies is required to consume binaries built with the IR backend.
      // It doesn't change the bytecode that gets generated for this module.
      "-Xallow-jvm-ir-dependencies",
      "-Xopt-in=kotlin.RequiresOptIn"
    )
  }
}

dependencies {
  implementation(Dependencies.AndroidXCore)
}
