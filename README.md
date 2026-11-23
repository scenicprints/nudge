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

**On the home screen.** A widget lists what is waiting on you, a coloured dot per kind, and
opens the app when tapped. A tricolour rail runs down its left whatever is happening, and with
nothing due it shows a chip per thing it is watching, so the state it is in nearly all of the
time is the one worth looking at. It names any bills on file too, which are otherwise invisible
until the morning before they are due. A reply nudge shows who is waiting but not what they
said, because a home screen is visible to anyone who can see the phone.

Quiet hours hold everything overnight except time to leave.

Swiping a nudge away only clears the shade. The nudge stays in the app and on the widget until
it expires or you press Done, because clearing a notification is housekeeping and not an answer.

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
- `ui/Home.kt` is both screens and holds no logic. `NudgesPage` is what the app opens on —
  what is waiting, what is coming, what is on file, and anything still to be granted.
  `SettingsPage` is what to watch, quiet hours and the version, behind the Settings button,
  because none of it is about today. Two pages is a flag and a `BackHandler`, not a
  navigation library. Which page you are on is not saved: anything that opens the app is
  asking for the nudges, so a tap on the widget or a nudge comes home rather than reopening
  wherever you last were.
- `ui/Theme.kt` is the palette. Grey, and colour that means one thing: this is waiting on you.
  `now` is the app's own amber, for the line under the title and the count on the widget.
  `reply`, `leave` and `bill` say which of the three a nudge is, on its rail, its kind label,
  its button and its dot on the widget. A nudge that is only coming is drawn in grey and takes
  its colour when it speaks up, so colour is never decoration: where you see it, something is
  waiting. The dark set is bright enough to carry on the dark panel and the light set dark
  enough to carry on white, which is what lets `ground` be the text colour on top of any of
  them. The widget repeats all of it in `res/values/colors.xml` and `res/values-night/colors.xml`.
- `widget/NudgeWidget.kt` is the home screen widget, built with RemoteViews rather than Glance
  so that it adds no dependency. `Brain` redraws it whenever the list changes, so it asks for
  no update period of its own and never wakes the device on its own account. A launcher cannot
  see a Compose theme, so the palette is repeated as colour resources in `res/values/colors.xml`
  and `res/values-night/colors.xml`. Change a colour in one place and change it in the other.

  It has four states and each says something true: a count and a row per nudge when something
  is waiting; the same rows, grey, when something is only coming; a chip per thing it watches
  when there is nothing on file; and what is missing when it is not watching. The headline
  appears only in the last two, because with rows on screen it would just restate them.

  The rail and the chips are deliberately on the state with nothing due. Colour that only
  arrives with a nudge is colour you almost never see, because a widget with nothing waiting is
  what this one shows nearly all of the time.

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
listener, alarms and inline reply have not been exercised against real apps. The widget has
not been placed on a home screen, so its size and wrapping are untested.
