---
description: Record the connected phone's logs while the user reproduces an AOD Screen problem, then, when the user signals, find out why the AOD Screen closed. Use when the AOD Screen disappears or the lock screen appears on its own, for example after the phone was idle and then picked up.
argument-hint: [what you are about to reproduce]
---

Monitor the phone while the user reproduces: `$ARGUMENTS`

The command has two phases. Phase 1 starts the recording and ends your turn. Phase 2 runs when the
user sends their next message (the signal). Do not poll or sleep in between.

`ADB` below is `C:/android/sdk/platform-tools/adb`. Log files go in this session's scratchpad
directory. Always give the user their full absolute paths.

## Phase 1: start recording

1. Run `"$ADB" devices`. Exactly one device must be listed. If none is listed, ask the user to
   connect the phone and stop.
2. Run `"$ADB" shell pm list packages app.aodkit`. If aodkit is not installed, say so and stop.
3. Run `"$ADB" shell date` and note the phone's time; the user's times refer to it.
4. Run `"$ADB" logcat -c`. This clears the main and system buffers. The events buffer keeps hours
   of history, which you need, so leave it.
5. Start the live recording in the background (`run_in_background`) with no timeout:
   `"$ADB" logcat -v threadtime -b main,system,events > "<scratchpad>/aod-monitor-<yyyyMMdd-HHmmss>.txt"`
6. Tell the user:
   - recording is running, with the file path
   - reproduce the problem, taking as long as needed
   - unplugging the USB cable ends the live recording (the task exits with code 255), but the
     phone keeps its own recent logs, so plug it back in before signalling
   - then send any message as the signal, ideally with the approximate time and what they did
     (picked the phone up, unplugged the charger, looked at it, touched the screen)
7. End your turn. If the background task finishes before the signal, do not restart it; just
   mention it in phase 2.

## Phase 2: on the user's signal

1. Stop the live recording if it is still running (TaskStop).
2. Run `"$ADB" devices`. If no device is listed, ask the user to plug the phone in and wait.
3. Dump the phone's buffers. This fills any gap left by a disconnect:
   `"$ADB" logcat -d -v threadtime -b main,system,events > "<scratchpad>/aod-monitor-<same stamp>-dump.txt"`
4. Analyse both files with Grep. They run to several MB, so don't read them whole.

### 4a. List AOD Screen instances

Grep the events lines `wm_create_activity|wm_finish_activity|wm_destroy_activity` that contain
`aodkit/.AodActivity`. For each instance, list its start, its end and the finish reason:

- `app-request`: aodkit itself called `finish()`. Go to 4c.
- `proc died without state saved`: the process was killed. Grep
  `am_kill|am_proc_died|lowmemorykiller` for `app.aodkit` to find out why (memory, battery, or the
  app being updated).

Pick the exit that matches the user's time and description. If unsure, take the last exit before
the signal.

### 4b. Look at what happened around that exit

Print every line from 10 s before the exit to 2 s after it. Drop the noise lines: `SurfaceFlinger`,
`CAE `, `SemWifi`, `WifiTile`, `io_stats`, `NetdEvent`, `BatteryExternal`, `suspend-service` and
`power_partial_wake_state`. Then look for these triggers in that window:

- **Charger:** `battery_status`, `charging_state` (charger plugged in or unplugged).
- **Screen off or on:** `Going to sleep`, `Waking up from` (the reason follows "due to").
- **Proximity:** `ACTION_SCREEN_OFF_BY_PROXIMITY`, `ACTION_SCREEN_ON_BY_PROXIMITY`,
  `Applying proximity`.
- **Something covering the AOD Screen:** `scheduleTopResumedActivityChanged` with `AodActivity`,
  `START u0`.
- **Unlocking:** `dismiss_screen`, `keyguardGoingAway`, `USER_PRESENT`,
  `onAuthenticationSucceeded`.
- **Face recognition on lift:** `Pick Up Gesture`, `LiftToRunFaceAuth`, `authEndedFor`.
- **A touch on the AOD Screen:** `VRI[AodActivity]` together with `ViewPostIme pointer`.

### 4c. Match an `app-request` exit to aodkit code

Grep `finish` in `app/src/main/java/app/aodkit/` to get the current call sites, and match the
evidence to one of them. At the time of writing they were:

- `AodActivity.onStop`: something covered the AOD Screen while the display was still on
- the `USER_PRESENT` receiver: the phone was unlocked
- the sensor ring touch, or the swipe-to-lock-screen unlock method
- `unlockThen`: after a successful unlock
- `AodNotificationListener.onScreenOffWhileAodShown`: the display turned off while the AOD Screen was up

## 5. Report

- A short timeline (time, event, the log line that shows it).
- The cause, and the aodkit code path it went through.
- A proposed fix. Do not change any code until the user agrees.
- The full paths of both log files.

## Known lead (2026-11-27)

One AOD Screen closed 2 s after a charger plug or unplug: `battery_status` and the `charging_state`
notification at 14:12:05, then `wm_finish_activity … app-request` at 14:12:07. Check for this
first.
