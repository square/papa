# Square Papa

_Performance of Android Production Applications_

**This library is not stable for usage beyond Square, the APIs and internals might change anytime.**

Papa... ?

## Table of contents

* [Usage](#usage)
* [FAQ](#faq)
* [License](#license)

_We still need a logo!_
![logo_512.png](assets/logo_512.png)

## Usage

Add the `papa` dependency to your library or app's `build.gradle` file:

```gradle
dependencies {
  implementation 'com.squareup.papa:papa:0.22'
}
```

Then install a `PapaEventListener` (see `PapaEvent` for details):

```kotlin
import android.app.Application
import papa.PapaEvent.AppLaunch
import papa.PapaEventListener
import papa.PapaEventLogger

class ExampleApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    if (BuildConfig.DEBUG) {
      PapaEventListener.install(PapaEventLogger())
    }

    PapaEventListener.install { event ->
      when (event) {
        is AppLaunch -> {
          TODO("Log to analytics")
        }
      }
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
