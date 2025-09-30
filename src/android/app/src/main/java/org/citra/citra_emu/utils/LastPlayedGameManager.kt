package org.citra.citra_emu.utils

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.model.Game

object LastPlayedGameManager {
    private const val KEY_LAST_PLAYED_GAME = "last_played_game"

    private val json get() = Json

    fun save(game: Game) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
        try {
            val payload = json.encodeToString(game)
            prefs.edit().putString(KEY_LAST_PLAYED_GAME, payload).apply()
        } catch (_: Exception) {
            prefs.edit().remove(KEY_LAST_PLAYED_GAME).apply()
        }
    }

    fun clear() {
        PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
            .edit()
            .remove(KEY_LAST_PLAYED_GAME)
            .apply()
    }

    fun load(): Game? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
        val stored = prefs.getString(KEY_LAST_PLAYED_GAME, null) ?: return null
        return try {
            json.decodeFromString<Game>(stored)
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun resolveLaunchableGame(): Game? {
        val saved = load() ?: return null
        if (!saved.isInstalled) {
            val uri = Uri.parse(saved.path)
            val exists = try {
                DocumentFile.fromSingleUri(CitraApplication.appContext, uri)?.exists() == true
            } catch (_: Exception) {
                false
            }
            if (!exists) {
                clear()
                return null
            }
        } else {
            val installed = try {
                NativeLibrary.getInstalledGamePaths().any { it == saved.path }
            } catch (_: Exception) {
                false
            }
            if (!installed) {
                clear()
                return null
            }
        }
        return saved
    }
}
