package com.leaf.lemonade.ui

import com.leaf.lemonade.net.CloudPlace

/** Actions panels can ask the activity to perform (they need file pickers or rebuild the UI). */
interface MainActions {
    fun exportSelection()
    fun addSelectionToToolbox()
    fun importToToolbox()
    /** Lets the user pick where to save a file, then writes what [producer] returns. */
    fun saveBytesAs(fileName: String, producer: () -> ByteArray?)
    /** Opens a place downloaded from Roblox (asks about unsaved changes first). */
    fun openPlaceBytes(name: String, bytes: ByteArray, cloud: CloudPlace?)
    /** Picks a plugin file from storage. */
    fun pickPluginFile()
    /** Re-styles the whole UI after a theme change. */
    fun applyThemeChange()
    /** Called after a plugin was installed or removed. */
    fun pluginsChanged()
}
