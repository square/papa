import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("com.android.library")
  kotlin("android")
  id("com.vanniktech.maven.publish")
}

android {
  compileSdk = 31

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  resourcePrefix = "papa_"

  defaultConfig {
    minSdk = 21
  }
  namespace = "com.squareup.papa.safetrace"

  buildFeatures {
    buildConfig = false
  }
}

tasks.withType<KotlinCompile> {
  kotlinOptions {
    freeCompilerArgs = listOfNotNull(
      "-Xopt-in=kotlin.RequiresOptIn"
    )
  }
}

dependencies {
  implementation(project(":papa-main-trace"))

  compileOnly(Dependencies.Build.AndroidXAnnotation)

  implementation(Dependencies.AndroidXTracing)
}
