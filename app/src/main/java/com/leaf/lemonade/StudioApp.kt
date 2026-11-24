package com.leaf.lemonade

import android.app.Application
import com.leaf.lemonade.core.Settings
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.ui.Ui

/** Keeps the open place alive across activity recreation. */
class StudioApp : Application() {
    val studio: Studio by lazy { Studio(this) }
    /** Plugins and the welcome message are set up once per process, not per activity. */
    var started = false
    /** A place has been opened or created (the start page can go back to it). */
    var placeOpened = false

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Ui.applyTheme(Settings.theme)
    }
}
