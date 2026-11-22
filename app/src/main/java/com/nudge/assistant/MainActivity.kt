package com.nudge.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nudge.assistant.data.Prefs
import com.nudge.assistant.data.Store
import com.nudge.assistant.notify.Actions
import com.nudge.assistant.sources.Perms
import com.nudge.assistant.ui.Grant
import com.nudge.assistant.ui.Home
import com.nudge.assistant.ui.HomeEvents
import com.nudge.assistant.ui.NudgeTheme
import com.nudge.assistant.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * How many times something outside the app has asked for it. The launch mode is singleTask,
     * so a tap on the widget or a nudge arrives as a new intent on the activity that is already
     * there, with nothing rebuilt; counting them is what lets the screen come home.
     */
    private val opens = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { NudgeTheme { Root(opens.intValue) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opens.intValue++
    }
}

@Composable
private fun Root(opens: Int) {
    val c = LocalContext.current
    val activity = c as ComponentActivity
    val scope = rememberCoroutineScope()
    val nudges by Store.flow.collectAsStateWithLifecycle()
    var perms by remember { mutableStateOf(Perms.read(c)) }
    var settings by remember { mutableStateOf(Prefs.settings(c)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var updateLabel by remember { mutableStateOf("Check for update") }
    var found by remember { mutableStateOf<Updater.Release?>(null) }

    // Permissions change in Settings, outside the app, so they are re-read on every return.
    LifecycleResumeEffect(Unit) {
        perms = Perms.read(c)
        now = System.currentTimeMillis()
        Brain.kick(c)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    fun appSettings() = c.startActivity(
        Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
    )

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        perms = Perms.read(c)
        Brain.kick(c)
        // A second refusal makes Android stop showing the dialog. Settings is the only way left.
        val refused = result.filterValues { !it }.keys
        if (refused.isNotEmpty() && refused.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) {
            appSettings()
        }
    }

    val labels = remember { HashMap<String, String>() }
    val labelOf: (String) -> String = { pkg ->
        labels.getOrPut(pkg) {
            runCatching {
                c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
    }

    val events = HomeEvents(
        grant = { g ->
            when (g) {
                Grant.NOTIFICATION_ACCESS ->
                    c.startActivity(Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                Grant.POST -> ask.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                Grant.CALENDAR -> ask.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                Grant.LOCATION -> ask.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
                Grant.BACKGROUND_LOCATION -> ask.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
        },
        settings = {
            settings = it
            Prefs.save(c, it)
            Brain.kick(c)
        },
        open = { Actions.open(c, it) },
        done = { Actions.done(c, it.id) },
        later = { Actions.later(c, it.id) },
        reply = { n, text ->
            if (!Actions.reply(c, n.id, text)) {
                Toast.makeText(c, "Could not send from here. Opening the app.", Toast.LENGTH_SHORT).show()
                Actions.open(c, n)
            }
        },
        update = {
            scope.launch {
                val release = found
                if (release == null) {
                    updateLabel = "Checking"
                    val latest = Updater.latest()
                    when {
                        latest == null -> updateLabel = "Could not check"
                        latest.versionCode > Updater.currentVersionCode(c) -> {
                            found = latest
                            updateLabel = "Install ${latest.name}"
                        }
                        else -> updateLabel = "Up to date"
                    }
                } else if (Updater.needsInstallPermission(c)) {
                    Updater.openInstallPermissionSettings(c)
                } else {
                    updateLabel = "Downloading"
                    val apk = Updater.download(c, release)
                    if (apk == null) updateLabel = "Download failed" else {
                        updateLabel = "Install ${release.name}"
                        Updater.install(c, apk)
                    }
                }
            }
        }
    )

    Home(
        nudges = nudges,
        perms = perms,
        settings = settings,
        now = now,
        version = remember { runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "" },
        updateLabel = updateLabel,
        labelOf = labelOf,
        on = events,
        opens = opens
    )
}
