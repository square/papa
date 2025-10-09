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
  }
  namespace = "com.squareup.papa.dev.receivers"

  buildFeatures {
    buildConfig = false
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
  compileOnly(Dependencies.Build.AndroidXAnnotation)

  implementation(project(":papa"))
}
