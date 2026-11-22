package com.nudge.assistant

import android.app.Application
import com.nudge.assistant.data.Store
import com.nudge.assistant.notify.Notifier

class NudgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.channels(this)
        Store.load(this)
        // Whatever started the process, one pass makes sure the next alarm is set.
        Brain.kick(this)
    }
}
