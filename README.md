# Nudge

A proactive assistant for a Pixel 11 Pro XL. It watches three things and speaks up only when
one of them needs you. Kotlin, Jetpack Compose, one module, nothing leaves the phone.

## What it does

**Unanswered messages.** A message arrives, you see it, you swipe it away meaning to answer
later. Thirty minutes on, Nudge says "You have not replied to Sam", with a reply field in the
notification that sends through the original app.

**Time to leave.** A calendar event with a place gets "Leave by 2:40 for Dentist", ten minutes
before you need to go, with a Navigate button.

**Bills due.** Any notification that reads like a payment-due notice is put on one list, and
mentioned again at 9 the morning before the due date.

Quiet hours hold everything overnight except time to leave.

## What it cannot know

These are limits of what Android lets an app see, not things left undone.

- **Read in the app but never answered is not nudged.** When you open a conversation the
  messaging app withdraws its notification, and from outside there is no way to tell whether
  you then typed a reply. Nudge treats that as handled. It only nudges for messages left
  sitting in the shade or swiped away. A wrong nudge costs more than a missed one.
- **Travel time is an estimate.** It comes from straight-line distance, not a routed time with
  traffic, which needs a paid routing API and a key. The wording says "about", and the "extra
  time to allow" setting is there to absorb the error.
- **Reply from the nudge works only while the handle is held.** The handle into the
  conversation lives in memory. If Android has restarted the app since the message arrived,
  the Reply button is left off and tapping opens the messaging app instead.
- **Bills are found by pattern.** The words "due" and one of payment, bill, statement,
  invoice, balance, amount, minimum, rent, premium, autopay. Bills that arrive only by email
  are seen only if the mail app's notification shows that text.

## Permissions it asks for, and why

| Permission | Without it |
|---|---|
| Notification access | No message or bill nudges |
| Send notifications | Nudges appear only inside the app |
| Calendar | No time to leave |
| Location | No time to leave |
| Location, all the time | Travel is measured from where you last opened the app |
| Install unknown apps | Cannot self-update |

## Structure

The **engine decides, everything else fetches and displays**. `engine/` is plain Kotlin with
no Android imports: `ReplyRule`, `LeaveRule`, `BillRule`, `Quiet`. Each takes plain data and
returns a `Nudge` or nothing. Behaviour changes go there, with a case in `EngineTest`.

- `Brain.kt` gathers inputs, calls the rules, stores results, announces what is due, and sets
  one alarm for the next moment anything could become due.
- `notify/NudgeListener.kt` reads notifications. `notify/Notifier.kt` posts nudges and handles
  their buttons.
- `sources/Sources.kt` is the calendar, position and geocoder.
- `data/Store.kt` is every nudge on file, mirrored to one JSON file.
- `ui/Home.kt` is the whole screen and holds no logic. `ui/Theme.kt` is the palette: grey, and
  one amber that means "this is waiting on you" and is used for nothing else.

## Building

There is no Gradle wrapper in the repo. CI provides Gradle 8.14.3 and JDK 17. Every push to
`main` runs the engine tests, builds a release APK and publishes it as release `v<run number>`.

```bash
gradle :app:testReleaseUnitTest :app:assembleRelease
```

Signing is optional. Set `NUDGE_KEYSTORE_BASE64`, `NUDGE_STORE_PASSWORD`, `NUDGE_KEY_ALIAS` and
`NUDGE_KEY_PASSWORD` as repo secrets and the APK is signed with a stable key, which is what
lets it upgrade in place. Without them it is signed with the debug key of whatever machine
built it, and an APK from a different machine will not install over it.

## Updating

"Check for update" at the bottom of the screen reads the latest GitHub release of
`scenicprints/nudge`, downloads the APK and hands it to the installer. The repo name is
`Updater.REPO`. The releases must be public for this to work without a token.

## Not yet verified

The engine has 23 passing tests, and the app compiles, lints clean and builds a release APK.
It has not yet been run on a phone, so the screen has not been looked at and the notification
listener, alarms and inline reply have not been exercised against real apps.
