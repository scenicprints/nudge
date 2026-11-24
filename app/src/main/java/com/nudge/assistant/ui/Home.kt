package com.nudge.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nudge.assistant.data.Settings
import com.nudge.assistant.engine.Ignore
import com.nudge.assistant.engine.Kind
import com.nudge.assistant.engine.NEVER
import com.nudge.assistant.engine.Nudge
import com.nudge.assistant.engine.Times
import com.nudge.assistant.sources.Perms
import java.time.ZoneId

enum class Grant { NOTIFICATION_ACCESS, POST, CALENDAR, LOCATION, BACKGROUND_LOCATION }

/** Everything the screen can ask for. The screen itself holds no logic and touches no system. */
class HomeEvents(
    val grant: (Grant) -> Unit = {},
    val settings: (Settings) -> Unit = {},
    val open: (Nudge) -> Unit = {},
    val done: (Nudge) -> Unit = {},
    val later: (Nudge) -> Unit = {},
    val reply: (Nudge, String) -> Unit = { _, _ -> },
    val update: () -> Unit = {}
)

private class Setup(val grant: Grant, val name: String, val why: String)

private fun missing(p: Perms, s: Settings): List<Setup> = buildList {
    if (!p.notificationAccess && (s.replyOn || s.billOn)) add(
        Setup(Grant.NOTIFICATION_ACCESS, "Notification access", "To see messages and bill notices as they arrive.")
    )
    if (!p.post) add(Setup(Grant.POST, "Send notifications", "So a nudge can reach you when the app is closed."))
    if (s.leaveOn) {
        if (!p.calendar) add(Setup(Grant.CALENDAR, "Calendar", "To know what you have on and where it is."))
        if (!p.location) add(Setup(Grant.LOCATION, "Location", "To work out how far away it is."))
        else if (!p.backgroundLocation) add(
            Setup(Grant.BACKGROUND_LOCATION, "Location, all the time", "Otherwise travel is measured from where you last opened this app.")
        )
    }
}

@Composable
fun Home(
    nudges: List<Nudge>,
    perms: Perms,
    settings: Settings,
    now: Long,
    version: String,
    updateLabel: String,
    labelOf: (String) -> String,
    on: HomeEvents,
    /** Bumped whenever something outside the app asks for it. Brings the nudges back up. */
    opens: Int = 0,
    zone: ZoneId = ZoneId.systemDefault()
) {
    val shown = nudges.filter { !it.dismissed }
    val current = shown.filter { it.announced }.sortedBy { it.expiresAt }
    val coming = shown.filter { it.isPending() }.sortedBy { it.fireAt }
    val filed = shown.filter { !it.announced && it.fireAt == NEVER }
    val setup = missing(perms, settings)

    // Two pages, so a flag and not a navigation library.
    //
    // Deliberately remember and not rememberSaveable. Saved state outlives the activity, so a
    // settings page left open came back on the next launch: tapping the widget or a nudge
    // opened the app on Settings. The nudges are what the app is for, and anything that opens
    // it is asking for those. [opens] counts the intents that arrive while the app is already
    // running, which singleTask delivers without rebuilding anything, so those come home too.
    var showSettings by remember { mutableStateOf(false) }
    LaunchedEffect(opens) { showSettings = false }
    BackHandler(enabled = showSettings) { showSettings = false }

    if (showSettings) {
        SettingsPage(settings, version, updateLabel, on) { showSettings = false }
    } else {
        NudgesPage(current, coming, filed, setup, now, zone, labelOf, on) { showSettings = true }
    }
}

/**
 * The page the app opens on: what is waiting, what is coming, and what is on file. Setting up
 * stays here rather than moving to the settings page, because an ungranted permission is not a
 * preference — it is the app not working, and it belongs where it will be seen.
 */
@Composable
private fun NudgesPage(
    current: List<Nudge>,
    coming: List<Nudge>,
    filed: List<Nudge>,
    setup: List<Setup>,
    now: Long,
    zone: ZoneId,
    labelOf: (String) -> String,
    on: HomeEvents,
    openSettings: () -> Unit
) {
    val p = LocalPalette.current
    Sheet {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Nudge", style = Type.title, color = p.ink)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            current.size == 1 -> "One thing needs you."
                            current.size > 1 -> "${current.size} things need you."
                            setup.any { it.grant == Grant.NOTIFICATION_ACCESS } -> "Not watching yet. Finish setting up below."
                            else -> "Nothing needs you right now."
                        },
                        style = Type.body, color = if (current.isEmpty()) p.dim else p.now
                    )
                }
                Spacer(Modifier.width(12.dp))
                Pill("Settings", filled = false, onClick = openSettings)
            }
        }

        if (setup.isNotEmpty()) {
            item { Label("Set up") }
            item {
                Panel {
                    setup.forEachIndexed { i, s ->
                        if (i > 0) Rule()
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = Type.head, color = p.ink)
                                Text(s.why, style = Type.body, color = p.dim)
                            }
                            Spacer(Modifier.width(12.dp))
                            Pill("Allow", filled = true) { on.grant(s.grant) }
                        }
                    }
                }
            }
        }

        if (current.isNotEmpty()) {
            item { Label("Now") }
            items(current, key = { it.id }) { Card(it, live = true, now, zone, labelOf, on) }
        }
        if (coming.isNotEmpty()) {
            item { Label("Coming up") }
            items(coming, key = { it.id }) { Card(it, live = false, now, zone, labelOf, on) }
        }
        if (filed.isNotEmpty()) {
            item { Label("On file") }
            items(filed, key = { it.id }) { Card(it, live = false, now, zone, labelOf, on) }
        }
    }
}

/** What to watch, quiet hours and the version. Nothing here is about today. */
@Composable
private fun SettingsPage(
    settings: Settings,
    version: String,
    updateLabel: String,
    on: HomeEvents,
    done: () -> Unit
) {
    val p = LocalPalette.current
    Sheet {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", style = Type.title, color = p.ink, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Pill("Done", filled = false, onClick = done)
            }
        }

        item { Label("What to watch") }
        item {
            Panel {
                Toggle("Unanswered messages", "A message you saw and left.", settings.replyOn) {
                    on.settings(settings.copy(replyOn = it))
                }
                if (settings.replyOn) {
                    Rule()
                    Stepper("Wait before nudging", minutes(settings.replyDelayMin), REPLY_DELAYS, settings.replyDelayMin) {
                        on.settings(settings.copy(replyDelayMin = it))
                    }
                    Rule()
                    Toggle("Group chats too", null, settings.includeGroups) {
                        on.settings(settings.copy(includeGroups = it))
                    }
                }
                Rule()
                Toggle("Time to leave", "Calendar events that have a place.", settings.leaveOn) {
                    on.settings(settings.copy(leaveOn = it))
                }
                if (settings.leaveOn) {
                    Rule()
                    Stepper("Extra time to allow", minutes(settings.leaveBufferMin), BUFFERS, settings.leaveBufferMin) {
                        on.settings(settings.copy(leaveBufferMin = it))
                    }
                }
                Rule()
                Toggle("Bills due", "Payment notices, mentioned again the day before.", settings.billOn) {
                    on.settings(settings.copy(billOn = it))
                }
            }
        }

        item { Label("Never nudge about") }
        item {
            Panel {
                // The two offered by default, plus anything else already on the list.
                val apps = (Ignore.DEFAULTS + settings.ignoredApps).sortedBy { Ignore.name(it) }
                apps.forEachIndexed { i, pkg ->
                    if (i > 0) Rule()
                    Toggle(
                        Ignore.name(pkg),
                        if (i == 0) "No message or bill nudges from these apps." else null,
                        pkg in settings.ignoredApps
                    ) { ignore ->
                        val next = if (ignore) settings.ignoredApps + pkg else settings.ignoredApps - pkg
                        on.settings(settings.copy(ignoredApps = next))
                    }
                }
            }
        }

        item { Label("Quiet hours") }
        item {
            Panel {
                Toggle("Hold nudges overnight", "Time to leave still comes through.", settings.quietOn) {
                    on.settings(settings.copy(quietOn = it))
                }
                if (settings.quietOn) {
                    Rule()
                    Stepper("From", hour(settings.quietStart), HOURS, settings.quietStart) {
                        on.settings(settings.copy(quietStart = it))
                    }
                    Rule()
                    Stepper("Until", hour(settings.quietEnd), HOURS, settings.quietEnd) {
                        on.settings(settings.copy(quietEnd = it))
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Version $version", style = Type.body, color = p.dim, modifier = Modifier.weight(1f))
                Pill(updateLabel, filled = false, onClick = on.update)
            }
        }
    }
}

/** The ground, the insets and the spacing, so that both pages sit the same way. */
@Composable
private fun Sheet(content: LazyListScope.() -> Unit) {
    val p = LocalPalette.current
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val dir = LocalLayoutDirection.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(p.ground),
        contentPadding = PaddingValues(
            start = insets.calculateStartPadding(dir) + 20.dp,
            end = insets.calculateEndPadding(dir) + 20.dp,
            top = insets.calculateTopPadding() + 28.dp,
            bottom = insets.calculateBottomPadding() + 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

private val REPLY_DELAYS = listOf(5, 10, 15, 30, 45, 60, 120, 240)
private val BUFFERS = listOf(0, 5, 10, 15, 20, 30)
private val HOURS = (0..23).toList()

private fun minutes(m: Int) = when {
    m == 0 -> "None"
    m < 60 -> "$m min"
    m % 60 == 0 -> "${m / 60} hr"
    else -> "${m / 60} hr ${m % 60} min"
}

private fun hour(h: Int) = when {
    h == 0 -> "12 AM"
    h < 12 -> "$h AM"
    h == 12 -> "12 PM"
    else -> "${h - 12} PM"
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(), style = Type.label, color = LocalPalette.current.dim,
        modifier = Modifier.padding(top = 14.dp, start = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.panel)
            .border(1.dp, p.line, RoundedCornerShape(18.dp))
    ) { content() }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(LocalPalette.current.line))
}

@Composable
private fun Pill(
    text: String,
    filled: Boolean,
    enabled: Boolean = true,
    /** The fill, when this is the thing to do about a nudge. Ink when left out. */
    tint: Color? = null,
    onClick: () -> Unit
) {
    val p = LocalPalette.current
    // 48dp of touch height around a 38dp shape.
    Box(
        Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.height(38.dp).clip(RoundedCornerShape(19.dp))
                .then(
                    if (filled) Modifier.background(tint ?: p.ink)
                    else Modifier.border(1.dp, p.line, RoundedCornerShape(19.dp))
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, style = Type.button, color = if (filled) p.ground else p.ink, maxLines = 1)
        }
    }
}

@Composable
private fun Toggle(name: String, why: String?, value: Boolean, set: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) { set(!value) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.head, color = p.ink)
            if (why != null) Text(why, style = Type.body, color = p.dim)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = value, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = p.ground, checkedTrackColor = p.ink,
                uncheckedThumbColor = p.dim, uncheckedTrackColor = p.panel, uncheckedBorderColor = p.line
            )
        )
    }
}

@Composable
private fun Stepper(name: String, shown: String, steps: List<Int>, value: Int, set: (Int) -> Unit) {
    val p = LocalPalette.current
    val i = steps.indexOf(value).takeIf { it >= 0 } ?: steps.indexOfFirst { it >= value }.coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, style = Type.body, color = p.ink, modifier = Modifier.weight(1f))
        Step("−", "Less", i > 0) { set(steps[i - 1]) }
        Text(
            shown, style = Type.head, color = p.ink, maxLines = 1,
            modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Step("+", "More", i < steps.lastIndex) { set(steps[i + 1]) }
    }
}

@Composable
private fun Step(glyph: String, describe: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.width(48.dp).height(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = describe, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, style = Type.title, color = if (enabled) p.ink else p.line)
    }
}

private fun kindWord(k: Kind) = when (k) {
    Kind.REPLY -> "Reply"
    Kind.LEAVE -> "Leave"
    Kind.BILL -> "Bill"
}

/** The small line on the right of a card: when it happened, or when it will be raised. */
private fun meta(n: Nudge, live: Boolean, now: Long, zone: ZoneId): String = when {
    n.kind == Kind.REPLY && live -> "since ${Times.clock(n.stamp, zone)}"
    n.fireAt == NEVER || live -> ""
    n.fireAt - now < Times.DAY / 2 -> "nudge at ${Times.clock(n.fireAt, zone)}"
    else -> "nudge ${Times.day(n.fireAt, zone)}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Card(n: Nudge, live: Boolean, now: Long, zone: ZoneId, labelOf: (String) -> String, on: HomeEvents) {
    val p = LocalPalette.current
    var replying by remember(n.id) { mutableStateOf(false) }
    var draft by remember(n.id) { mutableStateOf("") }
    // Which of the three this is. Shown only once it is live, so that colour on the screen
    // always means the same thing: this one is waiting on you.
    val accent = when (n.kind) {
        Kind.REPLY -> p.reply
        Kind.LEAVE -> p.leave
        Kind.BILL -> p.bill
    }

    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(18.dp)).background(p.panel)
            .border(1.dp, p.line, RoundedCornerShape(18.dp))
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (live) accent else p.panel))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(kindWord(n.kind), n.pkg?.let(labelOf).takeIf { n.kind != Kind.BILL })
                        .joinToString("  ·  ").uppercase(),
                    style = Type.label, color = if (live) accent else p.dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Text(meta(n, live, now, zone), style = Type.label, color = p.dim, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            Text(n.title, style = Type.head, color = p.ink)
            if (n.body.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(n.body, style = Type.body, color = p.dim, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }

            if (replying) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val send = {
                        if (draft.isNotBlank()) {
                            on.reply(n, draft.trim())
                            replying = false
                            draft = ""
                        }
                    }
                    BasicTextField(
                        value = draft, onValueChange = { draft = it },
                        textStyle = Type.body.copy(color = p.ink),
                        cursorBrush = SolidColor(p.ink),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                            .background(p.ground).padding(horizontal = 12.dp, vertical = 11.dp),
                        decorationBox = { inner ->
                            if (draft.isEmpty()) Text("Reply to ${n.title}", style = Type.body, color = p.dim, maxLines = 1)
                            inner()
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Pill("Send", filled = true, enabled = draft.isNotBlank(), onClick = send)
                }
            }

            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (n.kind) {
                    Kind.REPLY -> {
                        if (!replying) Pill("Reply", filled = live, tint = accent) { replying = true }
                        Pill("Open", filled = false) { on.open(n) }
                        if (live) Pill("In an hour", filled = false) { on.later(n) }
                    }
                    Kind.LEAVE -> Pill("Navigate", filled = live, tint = accent) { on.open(n) }
                    Kind.BILL ->
                        Pill("Open ${n.pkg?.let(labelOf) ?: "app"}", filled = live, tint = accent) { on.open(n) }
                }
                Pill("Done", filled = false) { on.done(n) }
            }
        }
    }
}
