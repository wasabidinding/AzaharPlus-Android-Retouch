// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import androidx.preference.PreferenceManager
import org.citra.citra_emu.R
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.model.Game
import java.nio.IntBuffer
import java.util.Locale
import kotlin.math.min

object ShortcutHelper {
    private const val DEFAULT_VISIBLE_LIMIT = 4

    fun updateDynamicShortcuts(context: Context, games: List<Game>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return
        }

        val manager = context.getSystemService(ShortcutManager::class.java) ?: return

        val limit = try {
            min(manager.maxShortcutCountPerActivity, DEFAULT_VISIBLE_LIMIT)
        } catch (_: Exception) {
            DEFAULT_VISIBLE_LIMIT
        }
        if (limit <= 0) return

        val topGames = selectTopGames(context, games, limit)
        if (topGames.isEmpty()) {
            // 清空已有的动态快捷方式以避免展示过期数据
            try {
                manager.removeAllDynamicShortcuts()
            } catch (_: Exception) { }
            return
        }

        val newShortcuts = topGames.mapNotNull { buildShortcut(context, it) }

        // 如果与当前动态快捷方式的 id 集合一致，则跳过更新，减少频繁写入
        val currentIds = manager.dynamicShortcuts.map { it.id }
        val newIds = newShortcuts.map { it.id }
        if (currentIds == newIds) {
            return
        }

        try {
            manager.setDynamicShortcuts(newShortcuts)
        } catch (_: Exception) { }
    }

    fun selectTopGames(context: Context, games: List<Game>, requestedN: Int): List<Game> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val showSystem = prefs.getBoolean(Settings.PREF_SHOW_HOME_APPS, false)

        val filtered = games.filter { game -> if (showSystem) true else !game.isSystemTitle }

        val sorted = filtered.sortedWith(
            compareByDescending<Game> { prefs.getLong(it.keyLastPlayedTime, 0L) }
                .thenByDescending { prefs.getLong(it.keyAddedToLibraryTime, 0L) }
                .thenBy { it.title.lowercase(Locale.getDefault()) }
        )

        val n = min(requestedN, DEFAULT_VISIBLE_LIMIT)
        return sorted.take(n)
    }

    private fun buildShortcut(context: Context, game: Game): ShortcutInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null

        val id = if (game.titleId != 0L) game.titleId.toString() else game.path

        val icon: Icon = try {
            val bmp = toBitmap(game.icon)
            if (bmp != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Icon.createWithAdaptiveBitmap(bmp)
                } else {
                    Icon.createWithBitmap(bmp)
                }
            } else {
                Icon.createWithResource(context, R.mipmap.ic_launcher)
            }
        } catch (_: Exception) {
            Icon.createWithResource(context, R.mipmap.ic_launcher)
        }

        val intent = try {
            game.launchIntent.apply { putExtra("launched_from_shortcut", true) }
        } catch (_: Exception) {
            return null
        }

        return ShortcutInfo.Builder(context, id)
            .setShortLabel(game.title.take(24))
            .setIcon(icon)
            .setIntent(intent)
            .build()
    }

    private fun toBitmap(vector: IntArray?): Bitmap? {
        if (vector == null) return null
        return try {
            val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.RGB_565)
            bmp.copyPixelsFromBuffer(IntBuffer.wrap(vector))
            bmp
        } catch (_: Exception) {
            null
        }
    }
}



