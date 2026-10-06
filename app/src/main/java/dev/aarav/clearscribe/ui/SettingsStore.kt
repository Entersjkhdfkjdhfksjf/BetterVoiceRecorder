package dev.aarav.clearscribe.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tiny settings store — two values, SharedPreferences is plenty and avoids
 * pulling in DataStore for this little state.
 *
 * [modelsEverPrepared] is how "only download when the user says so" is
 * honored across app restarts: once the user has explicitly triggered a
 * prepare from Settings, later launches can silently re-run the (now
 * cache-hit, no-network) prepare step so the app doesn't nag every time —
 * see ClearScribeApp. Until then, nothing downloads or loads automatically.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("clearscribe_settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        AppTheme.valueOf(prefs.getString(KEY_THEME, AppTheme.GALAXY_ONE_UI.name)!!)
    )
    val theme: StateFlow<AppTheme> = _theme

    fun setTheme(theme: AppTheme) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        _theme.value = theme
    }

    var modelsEverPrepared: Boolean
        get() = prefs.getBoolean(KEY_MODELS_PREPARED, false)
        set(value) = prefs.edit().putBoolean(KEY_MODELS_PREPARED, value).apply()

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_MODELS_PREPARED = "models_ever_prepared"
    }
}
