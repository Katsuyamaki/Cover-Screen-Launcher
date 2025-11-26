package com.example.quadrantlauncher

import android.content.Context

object AppPreferences {

    private const val PREFS_NAME = "AppLauncherPrefs"
    private const val KEY_FAVORITES = "KEY_FAVORITES"
    private const val KEY_LAST_LAYOUT = "KEY_LAST_LAYOUT"
    private const val KEY_LAST_RESOLUTION = "KEY_LAST_RESOLUTION"
    private const val KEY_LAST_DPI = "KEY_LAST_DPI"
    private const val KEY_PROFILES = "KEY_PROFILES"
    private const val KEY_FONT_SIZE = "KEY_FONT_SIZE"
    private const val KEY_ICON_URI = "KEY_ICON_URI"
    
    // Settings
    private const val KEY_KILL_ON_EXECUTE = "KEY_KILL_ON_EXECUTE"
    private const val KEY_TARGET_DISPLAY_INDEX = "KEY_TARGET_DISPLAY_INDEX"
    private const val KEY_RESET_TRACKPAD = "KEY_RESET_TRACKPAD"
    private const val KEY_IS_MOVE_MODE = "KEY_IS_MOVE_MODE"
    
    // State Retention
    private const val KEY_LAST_QUEUE = "KEY_LAST_QUEUE"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun savePackage(context: Context, key: String, packageName: String) {
        getPrefs(context).edit().putString(key, packageName).apply()
    }

    fun loadPackage(context: Context, key: String): String? {
        return getPrefs(context).getString(key, null)
    }

    fun getSimpleName(pkg: String?): String {
        if (pkg == null) return "Select App"
        val name = pkg.substringAfterLast('.')
        return if (name.isNotEmpty()) name else pkg
    }

    fun getFavorites(context: Context): MutableSet<String> {
        return getPrefs(context).getStringSet(KEY_FAVORITES, mutableSetOf()) ?: mutableSetOf()
    }

    fun isFavorite(context: Context, packageName: String): Boolean {
        return getFavorites(context).contains(packageName)
    }

    fun toggleFavorite(context: Context, packageName: String): Boolean {
        val favorites = getFavorites(context)
        val newSet = HashSet(favorites)
        val isAdded: Boolean
        if (newSet.contains(packageName)) {
            newSet.remove(packageName)
            isAdded = false
        } else {
            newSet.add(packageName)
            isAdded = true
        }
        getPrefs(context).edit().putStringSet(KEY_FAVORITES, newSet).apply()
        return isAdded
    }
    
    fun saveLastLayout(context: Context, layoutId: Int) {
        getPrefs(context).edit().putInt(KEY_LAST_LAYOUT, layoutId).apply()
    }

    fun getLastLayout(context: Context): Int {
        return getPrefs(context).getInt(KEY_LAST_LAYOUT, 2)
    }

    fun saveLastResolution(context: Context, resIndex: Int) {
        getPrefs(context).edit().putInt(KEY_LAST_RESOLUTION, resIndex).apply()
    }

    fun getLastResolution(context: Context): Int {
        return getPrefs(context).getInt(KEY_LAST_RESOLUTION, 0)
    }

    fun saveLastDpi(context: Context, dpi: Int) {
        getPrefs(context).edit().putInt(KEY_LAST_DPI, dpi).apply()
    }

    fun getLastDpi(context: Context): Int {
        return getPrefs(context).getInt(KEY_LAST_DPI, -1)
    }

    // --- PROFILES ---
    
    fun getProfileNames(context: Context): MutableSet<String> {
        return getPrefs(context).getStringSet(KEY_PROFILES, mutableSetOf()) ?: mutableSetOf()
    }

    fun saveProfile(context: Context, name: String, layout: Int, resIndex: Int, dpi: Int, apps: List<String>) {
        val names = getProfileNames(context)
        val newNames = HashSet(names)
        newNames.add(name)
        getPrefs(context).edit().putStringSet(KEY_PROFILES, newNames).apply()

        val appString = apps.joinToString(",")
        val data = "$layout|$resIndex|$dpi|$appString"
        getPrefs(context).edit().putString("PROFILE_$name", data).apply()
    }

    fun getProfileData(context: Context, name: String): String? {
        return getPrefs(context).getString("PROFILE_$name", null)
    }

    fun deleteProfile(context: Context, name: String) {
        val names = getProfileNames(context)
        val newNames = HashSet(names)
        newNames.remove(name)
        getPrefs(context).edit().putStringSet(KEY_PROFILES, newNames).remove("PROFILE_$name").apply()
    }

    // --- FONT SIZE ---

    fun saveFontSize(context: Context, size: Float) {
        getPrefs(context).edit().putFloat(KEY_FONT_SIZE, size).apply()
    }

    fun getFontSize(context: Context): Float {
        return getPrefs(context).getFloat(KEY_FONT_SIZE, 16f)
    }

    // --- LAUNCHER ICON ---

    fun saveIconUri(context: Context, uri: String) {
        getPrefs(context).edit().putString(KEY_ICON_URI, uri).apply()
    }

    fun getIconUri(context: Context): String? {
        return getPrefs(context).getString(KEY_ICON_URI, null)
    }

    // --- SETTINGS ---
    
    fun setKillOnExecute(context: Context, kill: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_KILL_ON_EXECUTE, kill).apply()
    }

    fun getKillOnExecute(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_KILL_ON_EXECUTE, true)
    }

    fun setTargetDisplayIndex(context: Context, index: Int) {
        getPrefs(context).edit().putInt(KEY_TARGET_DISPLAY_INDEX, index).apply()
    }

    fun getTargetDisplayIndex(context: Context): Int {
        return getPrefs(context).getInt(KEY_TARGET_DISPLAY_INDEX, 1)
    }

    fun setResetTrackpad(context: Context, enable: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_RESET_TRACKPAD, enable).apply()
    }

    fun getResetTrackpad(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_RESET_TRACKPAD, false)
    }

    fun setMoveMode(context: Context, enable: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_MOVE_MODE, enable).apply()
    }

    fun getMoveMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_MOVE_MODE, false)
    }
    
    // --- STATE RETENTION ---
    fun saveLastQueue(context: Context, apps: List<String>) {
        val str = apps.joinToString(",")
        getPrefs(context).edit().putString(KEY_LAST_QUEUE, str).apply()
    }
    
    fun getLastQueue(context: Context): List<String> {
        val str = getPrefs(context).getString(KEY_LAST_QUEUE, "") ?: ""
        if (str.isEmpty()) return emptyList()
        return str.split(",").filter { it.isNotEmpty() }
    }
}
