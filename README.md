# Square Tart

_Tracing Action Response Times!_

**This library is not stable for usage beyond Square, the APIs and internals might change anytime.**

Tart... ?

## Table of contents

* [Usage](#usage)
* [FAQ](#faq)
* [License](#license)

_We still need a logo!_
![logo_512.png](assets/logo_512.png)

## Usage

Add the `tart` dependency to your library or app's `build.gradle` file:

```gradle
dependencies {
  implementation 'com.squareup.tart:tart:0.1'
}
```

Then add a new `AppLaunch.onAppLaunchListeners`. See `AppLaunch` javadoc for details.

```kotlin
import android.app.Application
import tart.AppLaunch
import tart.PreLaunchState

class ExampleApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    AppLaunch.onAppLaunchListeners += { appLaunch ->
      val startType = when (appLaunch.preLaunchState) {
        NO_PROCESS -> "cold start"
        NO_PROCESS_FIRST_LAUNCH_AFTER_INSTALL -> "cold start"
        NO_PROCESS_FIRST_LAUNCH_AFTER_UPGRADE -> "cold start"
        NO_PROCESS_FIRST_LAUNCH_AFTER_CLEAR_DATA -> "cold start"
        PROCESS_WAS_LAUNCHING_IN_BACKGROUND -> "warm start"
        NO_ACTIVITY_NO_SAVED_STATE -> "warm start"
        NO_ACTIVITY_BUT_SAVED_STATE -> "warm start"
        ACTIVITY_WAS_STOPPED -> "hot start"
      }
      val durationMillis = appLaunch.duration.uptime(MILLISECONDS)
      println("$startType launch: $durationMillis ms")
    }
  }
}


```

## FAQ

### It's not really a question, more like a comment..

## License

<pre>
Copyright 2021 Square Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>
