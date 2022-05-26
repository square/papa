Change Log
==========

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