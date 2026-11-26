# aodkit

An Android app that replaces the system Always On Display with its own interactive always-on screen.
It lets the user read notifications and reach favourite apps from the locked phone.

## Language

### Screens

**AOD Screen**:
aodkit's own always-on screen, shown over the lock screen while the phone is locked and the display would otherwise be off.
_Avoid_: AOD (alone), always-on overlay, ambient screen

**System AOD**:
The Always On Display built into the phone's OS (e.g. Samsung One UI's). aodkit replaces it, and the user is expected to turn it off.
_Avoid_: native AOD, stock AOD

**Lock Screen**:
The OS keyguard the user must pass (fingerprint, PIN, face) to unlock. The **AOD Screen** sits on top of it and never replaces it.
_Avoid_: keyguard (in UI copy)

### Notifications

**Notification Icon**:
One icon on the **AOD Screen** representing one app that has at least one visible notification. Exactly one per app, never one per notification.
_Avoid_: notification badge, bubble

**Notification Modal**:
The window opened by tapping a **Notification Icon**. It lists all of that app's visible notifications and is readable without unlocking.
_Avoid_: popup, preview, detail view

**Dismiss**:
Clearing a notification from the **Notification Modal**. No unlock is needed.
_Avoid_: delete, remove

**Open**:
Unlocking the phone and then launching the notification's own target in its app.
_Avoid_: launch, reply

**Hidden App**:
An app the user has excluded in settings, so it never gets a **Notification Icon**.
_Avoid_: blacklisted app, muted app

### Shortcuts

**Shortcut Launcher**:
The button in the bottom-right corner of the **AOD Screen** that reveals the user's **Shortcuts**.
_Avoid_: app drawer, menu button

**Shortcut**:
An app the user has pinned in settings for quick launch from the **AOD Screen**. Launching it always requires an unlock. At most 8, in a user-defined order.
_Avoid_: favourite, quick app, pinned app

## Example dialogue

> **Dev:** Slack has three notifications. Do we show three **Notification Icons**?
> **Expert:** No, one **Notification Icon** per app. Tapping it opens the **Notification Modal** with all three.
> **Dev:** If they **Dismiss** all three, does the modal stay open and empty?
> **Expert:** The **Notification Icon** disappears, because the app no longer has visible notifications.
> **Dev:** And if they tap Slack in the **Shortcut Launcher** instead?
> **Expert:** That's a **Shortcut**, not a notification. It goes through the **Lock Screen** first and then opens Slack normally. It has nothing to do with Slack's notifications.
> **Dev:** What if WhatsApp is a **Hidden App** but also a **Shortcut**?
> **Expert:** Both are fine. Hidden only means no **Notification Icon**, while the **Shortcut** still works.
