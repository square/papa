Change Log
==========

Version 0.11
_2023-03-13_

* Add `EventFrameLabeler` to leverage with `SafeTrace` and `WindowOverlay` to make it easy to match frames in Perfetto & on screen.
* Add `WindowOverlay` in `papa-dev` to facilitate drawing on top of the app windows.
* Add support for customization of the main thread section names (`SafeTraceSetup.mainThreadSectionNameMapper`)
* Make PerfAppComponentFactory public
* Rename papa-dev-receivers to papa-dev
* Add `OnEventScope.startSingleFrameInteraction()`, `OnEventScope.cancel()` and `OnEventScope.cancelRunningInteractions()`
* Add InteractionRuleClient.trackedInteractions``
* Rename `RunningInteraction.events` to `RunningInteraction.sentEvents`

Version 0.10
_2022-10-12_

* bugfix: never cleared finishing interactions
* Add support for registering against event interfaces

Version 0.8
_2022-10-06_

* Even more rewrite of the interaction latency APIs! Interactions are now just a list of events (no more start & end, no more interaction type)

Version 0.7
_2022-10-03_

* More rewrite of the interaction latency APIs. Adds reporting of start & end events, centralizes the handling of the ending of the interactions.
* Removing a rule cancels any interaction in flight.

Version 0.6

_2022-09-28_

* Rewrite of the APIs to track interaction latency to facilitate integration in a large codebase ([#38](https://github.com/square/papa/pull/38)). This also starts introducing the Kotlin `Duration` class in the APIs (instead of longs) and started replacing millis measurements with nanos measurements (the accumulated of rounding error led to results that were a few millis off). This also adds tracking of frame count per interaction ([#29](https://github.com/square/papa/issues/29)).
* Use MessageCompat.setAsynchronous to support API 16+ ([#33](https://github.com/square/papa/pull/33))
* `onCurrentFrameRendered()` joins multiple calls into a single post ([#35](https://github.com/square/papa/issues/35))

Version 0.5

_2022-05-26_

* Library name changed from `square/tart` to `square/papa`
* APIs entirely rewritten, most things are now done via PapaEventListener.install
* Removed legacy package.
* APIs still aren't stable but they're getting closer.

Version 0.4.1
-----------

_2021-10-28_

* Adding atrace traces for touch tracking, leveraging Jetpack tracing.

Version 0.3
-----------

_2021-9-30_

* Legacy FrozenFrameOnTouchDetector only reports cases where the input has a delay on delivery, rather than looking at total duration from input to frame. This is so that we don't do extra work on every touch down, i.e. previous released would traverse the view hierarchy looking for a pressed view on every touch down when really we just want it for frozen cases.
* New APIs: `isChoreographerDoingFrame()` and ` Window.onCurrentFrameDisplayed()`.

Version 0.2
-----------

_2021-9-28_


* Launch detection relies on a predraw listener instead of a draw listener for 1st frame (draw may be skipped if nothing to redraw)
* Update `CpuDuration` so that the time Unit is now part of its state instead of being millis. Now relying on nanos where possible. `duration.uptimeMillis` => `duration.uptime(MILLISECONDS)`.
* Added new pre launch state: NO_PROCESS_FIRST_LAUNCH_AFTER_CLEAR_DATA
* Fixed import errors that led to legacy APIs landing in the internal package instead of legacy.
* Instrumented CI and fixed flakes
* Improved last touch up recording and added last back recording (currently still a legacy API)
* FrozenFrameOnTouchDetector stopped reporting one frame late.

Version 0.1
-----------

_2021-9-23_

Initial release.

* Let's do this!