# Replace the System AOD with our own screen, launched via "Display over other apps" from the notification listener

Android offers no API for adding UI to the System AOD, so aodkit draws its own AOD Screen. This is
a black activity shown over the lock screen (`setShowWhenLocked`, `setTurnScreenOn`) at minimum
brightness. Whenever the display turns off, it is started from the background. The screen-off
receiver lives inside the `NotificationListenerService`, which the system keeps bound anyway. The
background start is allowed because the user grants `SYSTEM_ALERT_WINDOW`.

## Considered Options

- **Full-screen-intent notification.** Rejected: since Android 14, Play restricts
  `USE_FULL_SCREEN_INTENT` to calling and alarm apps.
- **Accessibility Service.** Rejected: it attracts heavy Play review and is far more power than
  needed.
- **Dedicated foreground service to host the receiver.** Rejected for the MVP: it needs a
  `specialUse` FGS type with Play justification and a permanent notification. Revisit if the
  listener is unbound too often on Samsung devices (we call `requestRebind` when it is).

## Consequences

- The display is technically on while the AOD Screen shows, so battery cost is higher than a true
  hardware AOD.
- The app never declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play-restricted). The user is only
  deep-linked to the battery settings.
- If a future Android release narrows the `SYSTEM_ALERT_WINDOW` background-start exemption, this
  decision must be revisited.
