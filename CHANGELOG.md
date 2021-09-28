Change Log
==========

Version 0.2
-------------

_2021-9-28_


* Launch detection relies on a predraw listener instead of a draw listener for 1st frame (draw may be skipped if nothing to redraw)
* Update `CpuDuration` so that the time Unit is now part of its state instead of being millis. Now relying on nanos where possible. `duration.uptimeMillis` => `duration.uptime(MILLISECONDS)`.
* Added new pre launch state: NO_PROCESS_FIRST_LAUNCH_AFTER_CLEAR_DATA
* Fixed import errors that led to legacy APIs landing in the internal package instead of legacy.
* Instrumented CI and fixed flakes
* Improved last touch up recording and added last back recording (currently still a legacy API)
* FrozenFrameOnTouchDetector stopped reporting one frame late.

Version 0.1
-------------

_2021-9-23_

Initial release.

* Let's do this!