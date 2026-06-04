// FILE ADDED BY AzaharPlus AUGUST 2025

package org.citra.citra_emu.fragments

import android.app.Dialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.Navigation
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.HomeNavigationDirections
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.model.Game
import androidx.preference.PreferenceManager
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.utils.FileUtil
import org.citra.citra_emu.utils.GameIconUtils
import org.citra.citra_emu.viewmodel.GamesViewModel
import org.citra.citra_emu.utils.OverlayPreferencesManager

class AboutGameBottomSheet : BottomSheetDialogFragment() {
    private lateinit var game: Game

    private var selectedIconBitmap: Bitmap? = null
    private var pendingCreateShortcutAfterPick: Boolean = false

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bitmap = decodeDownsampledBitmap(uri, 1024)
            if (bitmap != null) {
                val squared = cropCenterSquare(bitmap)
                val scaled = Bitmap.createScaledBitmap(squared, 512, 512, true)
                selectedIconBitmap = scaled
                if (pendingCreateShortcutAfterPick) {
                    createPinnedShortcut(selectedIconBitmap)
                    pendingCreateShortcutAfterPick = false
                }
            } else {
                Toast.makeText(requireContext(), R.string.loader_error_invalid_format, Toast.LENGTH_SHORT).show()
                pendingCreateShortcutAfterPick = false
            }
        } else {
            // User cancelled
            pendingCreateShortcutAfterPick = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            game = it.getParcelable(ARG_GAME) ?: throw IllegalArgumentException("Game argument missing")
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_about_game, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.about_game_title).text = game.title
        view.findViewById<TextView>(R.id.about_game_company).text = game.company
        view.findViewById<TextView>(R.id.about_game_region).text = game.regions
        view.findViewById<TextView>(R.id.about_game_id).text = getString(R.string.game_context_id) + " " + String.format("%016X", game.titleId)
        view.findViewById<TextView>(R.id.about_game_filename).text = getString(R.string.game_context_file) + " " + game.filename
        view.findViewById<TextView>(R.id.about_game_filetype).text = getString(R.string.game_context_type) + " " + game.fileType

        val playTimeSeconds = NativeLibrary.playTimeManagerGetPlayTime(game.titleId)
        view.findViewById<TextView>(R.id.about_game_playtime).text = buildString {
            val hours = playTimeSeconds / 3600
            val minutes = (playTimeSeconds % 3600) / 60
            val seconds = playTimeSeconds % 60
            val readablePlayTime = when {
                hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
            append("Playtime: ")
            append(readablePlayTime)
        }

        GameIconUtils.loadGameIcon(requireActivity(), game, view.findViewById(R.id.game_icon))

        // Insert Cartridge button
        val insertButton = view.findViewById<MaterialButton>(R.id.insert_cartridge_button)
        val insertable = game.isInsertable
        val prefs = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
        val inserted = insertable && (prefs.getString("insertedCartridge", "") == game.path)
        insertButton.text = if (inserted) getString(R.string.game_context_eject) else getString(R.string.game_context_insert)
        insertButton.visibility = if (insertable) View.VISIBLE else View.GONE
        insertButton.setOnClickListener {
            if (inserted) {
                prefs.edit().putString("insertedCartridge", "").apply()
            } else {
                prefs.edit().putString("insertedCartridge", game.path).apply()
            }
            dismissAllowingStateLoss()
        }

        // Compress/Decompress button
        val compressButton = view.findViewById<MaterialButton>(R.id.compress_decompress)
        compressButton.text = getString(if (!game.isCompressed) R.string.compress else R.string.decompress)
        if (game.isInstalled) {
            compressButton.setOnClickListener {
                Toast.makeText(requireContext(), getString(R.string.compress_decompress_installed_app), Toast.LENGTH_LONG).show()
            }
            compressButton.alpha = 0.38f
        } else {
            compressButton.setOnClickListener {
                val shouldCompress = !game.isCompressed
                val recommendedExt = NativeLibrary.getRecommendedExtension(game.path, shouldCompress)
                val baseName = game.filename.substringBeforeLast('.')
                // Trigger compress/decompress via the hosting activity/fragment
                Toast.makeText(requireContext(), getString(if (shouldCompress) R.string.compress else R.string.decompress) + ": $baseName.$recommendedExt", Toast.LENGTH_SHORT).show()
                dismissAllowingStateLoss()
            }
        }

        val preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
        val autoLoadStateKey = "auto_load_state_${game.titleId}"
        val autoLoadStateSwitch = view.findViewById<MaterialSwitch>(R.id.auto_load_state_switch)
        autoLoadStateSwitch.isChecked = preferences.getBoolean(autoLoadStateKey, true)
        autoLoadStateSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean(autoLoadStateKey, isChecked).apply()
        }

        val overlayFallback = overlayFallbackKey()
        val independentOverlaySwitch = view.findViewById<MaterialSwitch>(R.id.independent_overlay_switch)
        independentOverlaySwitch.isChecked = OverlayPreferencesManager.isIndependentEnabled(game.titleId, overlayFallback)
        independentOverlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            OverlayPreferencesManager.setIndependentEnabled(game.titleId, overlayFallback, isChecked)
            if (isChecked) {
                OverlayPreferencesManager.ensureGameProfile(game.titleId, overlayFallback)
            }
        }

        view.findViewById<MaterialButton>(R.id.about_game_play).setOnClickListener {
            val action = HomeNavigationDirections.actionGlobalEmulationActivity(game)
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(action)
        }

        // 图形 API 覆盖下拉菜单
        // API 选择值按 settings 风格展示为单行：标题左、值右
        val apiValueView = view.findViewById<TextView>(R.id.api_override_value)
        val perGameOverrideKey = "override_graphics_api_value_" + game.titleId // 0 system, 1 GL, 2 VK
        val items = listOf(
            getString(R.string.system_default),
            getString(R.string.opengles),
            getString(R.string.vulkan)
        )
        val savedValue = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext).getInt(perGameOverrideKey, 0)
        apiValueView.text = items.getOrElse(savedValue) { items[0] }
        // 点击整行或 value 弹出单选对话框
        val openChooser: (View) -> Unit = {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.graphics_api)
                .setSingleChoiceItems(items.toTypedArray(), savedValue) { dialog, which ->
                    PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
                        .edit().putInt(perGameOverrideKey, which).apply()
                    apiValueView.text = items[which]
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
        apiValueView.setOnClickListener(openChooser)
        view.findViewById<View>(R.id.api_row).setOnClickListener(openChooser)

        // LCD Shader 覆盖设置
        val lcdShaderValueView = view.findViewById<TextView>(R.id.lcd_shader_value)
        val perGameLcdOverrideKey = "override_lcd_shader_value_" + game.titleId // 0 system, 1 on, 2 off
        val lcdItems = listOf(
            getString(R.string.system_default),
            getString(R.string.lcd_effect),
            getString(R.string.none)
        )
        val savedLcdValue = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext).getInt(perGameLcdOverrideKey, 0)
        lcdShaderValueView.text = lcdItems.getOrElse(savedLcdValue) { lcdItems[0] }
        
        val openLcdChooser: (View) -> Unit = {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.post_processing_shader_name)
                .setSingleChoiceItems(lcdItems.toTypedArray(), savedLcdValue) { dialog, which ->
                    PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
                        .edit().putInt(perGameLcdOverrideKey, which).apply()
                    lcdShaderValueView.text = lcdItems[which]
                    
                    // Update the global setting for the current game
                    updatePerGameLcdSetting(game.titleId, which)
                    
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
        lcdShaderValueView.setOnClickListener(openLcdChooser)
        view.findViewById<View>(R.id.lcd_row).setOnClickListener(openLcdChooser)

        view.findViewById<MaterialButton>(R.id.game_shortcut).setOnClickListener {
            val shortcutManager = requireContext().getSystemService(ShortcutManager::class.java)
            if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported) {
                Toast.makeText(requireContext(), R.string.shortcut, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 弹出选项：默认图标 / 选择自定义图片
            val items = arrayOf(getString(R.string.option_default), getString(R.string.camera_select_image))
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.shortcut)
                .setItems(items) { dialog, which ->
                    when (which) {
                        0 -> {
                            // 使用当前 about 面板中的图作为图标
                            val defaultBitmap = (view.findViewById<ImageView>(R.id.game_icon).drawable as? BitmapDrawable)?.bitmap
                            createPinnedShortcut(defaultBitmap)
                        }
                        1 -> {
                            // 选择图片后立刻创建
                            pendingCreateShortcutAfterPick = true
                            pickImageLauncher.launch("image/*")
                        }
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }

        view.findViewById<MaterialButton>(R.id.cheats).setOnClickListener {
            val action = org.citra.citra_emu.features.cheats.ui.CheatsFragmentDirections.actionGlobalCheatsFragment(game.titleId)
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(action)
            dismissAllowingStateLoss()
        }

        view.findViewById<MaterialButton>(R.id.menu_button_open).setOnClickListener {
            showOpenContextMenu(it)
        }

        view.findViewById<MaterialButton>(R.id.menu_button_uninstall).setOnClickListener {
            showUninstallContextMenu(it)
        }

        // 移除直接在关于面板选择图标的入口，改为在"添加到主屏幕"流程中选择
    }

    private fun updatePerGameLcdSetting(titleId: Long, setting: Int) {
        // Update the per-game LCD setting in the native renderer
        NativeLibrary.updatePerGameLcdSetting(setting)
    }

    private fun createPinnedShortcut(srcBitmap: Bitmap?) {
        val shortcutManager = requireContext().getSystemService(ShortcutManager::class.java)
        if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported) {
            Toast.makeText(requireContext(), R.string.shortcut, Toast.LENGTH_SHORT).show()
            return
        }

        val iconBitmap = srcBitmap ?: (view?.findViewById<ImageView>(R.id.game_icon)?.drawable as? BitmapDrawable)?.bitmap
        if (iconBitmap == null) {
            Toast.makeText(requireContext(), R.string.loader_error_invalid_format, Toast.LENGTH_SHORT).show()
            return
        }

        val finalBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 为自适应图标增加边距，避免看起来被“放大”只显示中心区域
            padForAdaptiveIcon(iconBitmap)
        } else {
            iconBitmap
        }

        val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Icon.createWithAdaptiveBitmap(finalBitmap)
        } else {
            Icon.createWithBitmap(finalBitmap)
        }

        val shortcut = ShortcutInfo.Builder(requireContext(), game.titleId.toString())
            .setShortLabel(game.title)
            .setIcon(icon)
            .setIntent(game.launchIntent.apply {
                putExtra("launched_from_shortcut", true)
            })
            .build()
        shortcutManager.requestPinShortcut(shortcut, null)
    }

    private fun showOpenContextMenu(anchor: View) {
        val dirs = getGameDirectories()
        val popup = android.widget.PopupMenu(anchor.context, anchor).apply {
            menuInflater.inflate(R.menu.game_context_menu_open, menu)
            listOf(
                R.id.game_context_open_app to dirs.appDir,
                R.id.game_context_open_save_dir to dirs.saveDir,
                R.id.game_context_open_updates to dirs.updatesDir,
                R.id.game_context_open_dlc to dirs.dlcDir,
                R.id.game_context_open_extra to dirs.extraDir
            ).forEach { (id, dir) ->
                menu.findItem(id)?.isEnabled =
                    CitraApplication.documentsTree.folderUriHelper(dir)?.let {
                        DocumentFile.fromTreeUri(anchor.context, it)?.exists()
                    } ?: false
            }
        }

        popup.setOnMenuItemClickListener { menuItem ->
            val intent = Intent(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setType("*/*")

            val uri = when (menuItem.itemId) {
                R.id.game_context_open_app -> CitraApplication.documentsTree.folderUriHelper(dirs.appDir)
                R.id.game_context_open_save_dir -> CitraApplication.documentsTree.folderUriHelper(dirs.saveDir)
                R.id.game_context_open_updates -> CitraApplication.documentsTree.folderUriHelper(dirs.updatesDir)
                R.id.game_context_open_dlc -> CitraApplication.documentsTree.folderUriHelper(dirs.dlcDir)
                R.id.game_context_open_extra -> CitraApplication.documentsTree.folderUriHelper(dirs.extraDir)
                R.id.game_context_open_textures -> CitraApplication.documentsTree.folderUriHelper(dirs.texturesDir, true)
                R.id.game_context_open_mods -> CitraApplication.documentsTree.folderUriHelper(dirs.modsDir, true)
                else -> null
            }

            uri?.let {
                intent.data = it
                startActivity(intent)
                true
            } ?: false
        }

        popup.show()
    }

    private fun showUninstallContextMenu(anchor: View) {
        val dirs = getGameDirectories()
        val popup = android.widget.PopupMenu(anchor.context, anchor).apply {
            menuInflater.inflate(R.menu.game_context_menu_uninstall, menu)
            listOf(
                R.id.game_context_uninstall to dirs.gameDir,
                R.id.game_context_uninstall_dlc to dirs.dlcDir,
                R.id.game_context_uninstall_updates to dirs.updatesDir
            ).forEach { (id, dir) ->
                menu.findItem(id)?.isEnabled =
                    CitraApplication.documentsTree.folderUriHelper(dir)?.let {
                        DocumentFile.fromTreeUri(anchor.context, it)?.exists()
                    } ?: false
            }
        }

        val titleId = game.titleId
        val dlcTitleId = titleId or 0x8C00000000L
        val updateTitleId = titleId or 0xE00000000L

        popup.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.game_context_clear_shader_cache) {
                confirmClearShaderCache()
                return@setOnMenuItemClickListener true
            }

            val uninstallAction: () -> Unit = {
                when (menuItem.itemId) {
                    R.id.game_context_uninstall -> NativeLibrary.uninstallTitle(titleId, game.mediaType)
                    R.id.game_context_uninstall_dlc -> NativeLibrary.uninstallTitle(dlcTitleId, Game.MediaType.SDMC)
                    R.id.game_context_uninstall_updates -> NativeLibrary.uninstallTitle(updateTitleId, Game.MediaType.SDMC)
                }
                ViewModelProvider(requireActivity())[GamesViewModel::class.java].reloadGames(true)
                dismissAllowingStateLoss()
            }

            if (menuItem.itemId in listOf(R.id.game_context_uninstall, R.id.game_context_uninstall_dlc, R.id.game_context_uninstall_updates)) {
                IndeterminateProgressDialogFragment.newInstance(requireActivity(), R.string.uninstalling, false, uninstallAction)
                    .show(parentFragmentManager, IndeterminateProgressDialogFragment.TAG)
                true
            } else {
                false
            }
        }

        popup.show()
    }

    private fun confirmClearShaderCache() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.game_context_clear_shader_cache)
            .setMessage(getString(R.string.clear_shader_cache_confirmation, game.title))
            .setPositiveButton(R.string.clear) { _, _ ->
                IndeterminateProgressDialogFragment.newInstance(
                    requireActivity(),
                    R.string.clearing_shader_cache,
                    false
                ) {
                    val deleted = deleteShaderCacheFiles()
                    if (deleted > 0) {
                        CitraApplication.appContext.getString(R.string.clear_shader_cache_result, deleted)
                    } else {
                        CitraApplication.appContext.getString(R.string.clear_shader_cache_none)
                    }
                }.show(parentFragmentManager, IndeterminateProgressDialogFragment.TAG)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Deletes this title's on-disk shader and pipeline caches (OpenGL + Vulkan), mirroring the
     * desktop "Delete Shader Cache" action. Returns the number of files removed. Runs on a
     * background thread. Folders are located via the SAF-backed documents tree's folderUriHelper
     * (which, unlike resolvePath, has no path whitelist), then individual files are removed with
     * DocumentFile — so no native/core code is touched and only this title's caches are deleted.
     */
    private fun deleteShaderCacheFiles(): Int {
        val tree = CitraApplication.documentsTree
        val context = CitraApplication.appContext
        val id = String.format("%016X", game.titleId)
        var deleted = 0

        // (shader subdirectory, exact file name) pairs, all keyed by this title's ID. Other games'
        // caches live in the same folders, so we delete by exact name rather than wiping the dir.
        val targets = listOf(
            "shaders/opengl/transferable" to "$id.bin",
            "shaders/opengl/precompiled/separable" to "$id.bin",
            "shaders/opengl/precompiled/conventional" to "$id.bin",
            "shaders/vulkan/transferable" to "${id}_vs.vkch",
            "shaders/vulkan/transferable" to "${id}_fs.vkch",
            "shaders/vulkan/transferable" to "${id}_gs.vkch",
            "shaders/vulkan/transferable" to "${id}_pl.vkch"
        )
        targets.forEach { (dir, name) ->
            try {
                val dirUri = tree.folderUriHelper(dir) ?: return@forEach
                val file = DocumentFile.fromTreeUri(context, dirUri)?.findFile(name)
                if (file != null && file.delete()) deleted++
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to delete shader cache file $dir/$name: ${e.message}")
            }
        }

        // Vulkan driver pipeline cache files are named "<id>-<vendor><device>.bin"; the suffix
        // depends on the GPU, so enumerate the directory and match by title-id prefix.
        val pipelineFiles = try {
            val pipelineUri = tree.folderUriHelper("shaders/vulkan/pipeline")
            if (pipelineUri != null) DocumentFile.fromTreeUri(context, pipelineUri)?.listFiles() else null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to enumerate Vulkan pipeline cache: ${e.message}")
            null
        }
        // Delete each match in its own try so one failure doesn't skip the rest.
        pipelineFiles?.forEach { f ->
            try {
                val n = f.name
                if (n != null && n.startsWith(id) && f.delete()) deleted++
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to delete pipeline cache file ${f.name}: ${e.message}")
            }
        }

        return deleted
    }

    private data class GameDirectories(
        val gameDir: String,
        val saveDir: String,
        val modsDir: String,
        val texturesDir: String,
        val appDir: String,
        val dlcDir: String,
        val updatesDir: String,
        val extraDir: String
    )

    private fun getGameDirectories(): GameDirectories {
        val basePath = "sdmc/Nintendo 3DS/00000000000000000000000000000000/00000000000000000000000000000000"
        return GameDirectories(
            gameDir = game.path.substringBeforeLast("/"),
            saveDir = basePath + "/title/${String.format("%016x", game.titleId).lowercase().substring(0, 8)}/${String.format("%016x", game.titleId).lowercase().substring(8)}/data/00000001",
            modsDir = "load/mods/${String.format("%016X", game.titleId)}",
            texturesDir = "load/textures/${String.format("%016X", game.titleId)}",
            appDir = game.path.substringBeforeLast("/").split("/").filter { it.isNotEmpty() }.joinToString("/"),
            dlcDir = basePath + "/title/0004008c/${String.format("%016x", game.titleId).lowercase().substring(8)}/content",
            updatesDir = basePath + "/title/0004000e/${String.format("%016x", game.titleId).lowercase().substring(8)}/content",
            extraDir = basePath + "/extdata/00000000/${String.format("%016X", game.titleId).substring(8, 14).padStart(8, '0')}"
        )
    }

    private fun overlayFallbackKey(): String {
        return when {
            game.filename.isNotBlank() -> game.filename
            game.path.isNotBlank() -> game.path
            game.title.isNotBlank() -> game.title
            else -> "unknown"
        }
    }

    private fun decodeDownsampledBitmap(uri: Uri, targetMinSide: Int): Bitmap? {
        return try {
            val resolver = requireContext().contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sampleSize = 1
            val minSide = minOf(bounds.outWidth, bounds.outHeight)
            while (minSide / sampleSize > targetMinSide) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }

    private fun cropCenterSquare(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        return Bitmap.createBitmap(bitmap, x, y, size, size)
    }

    private fun padForAdaptiveIcon(source: Bitmap): Bitmap {
        // 确保输入是正方形
        val square = if (source.width == source.height) source else cropCenterSquare(source)
        val size = 512
        val canvasBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        // 内容缩放到画布的 75%，比之前多 5% 留白，避免系统规范化放大后只显示中心
        val scale = 0.75f
        val destSize = size * scale
        val left = (size - destSize) / 2f
        val top = (size - destSize) / 2f
        val dest = RectF(left, top, left + destSize, top + destSize)
        val srcScaled = if (square.width != size || square.height != size) {
            Bitmap.createScaledBitmap(square, size, size, true)
        } else square
        canvas.drawBitmap(srcScaled, null, dest, null)
        return canvasBitmap
    }

    companion object {
        const val TAG = "AboutGameBottomSheet"
        private const val ARG_GAME = "arg_game"

        fun newInstance(game: Game): AboutGameBottomSheet {
            val args = Bundle()
            args.putParcelable(ARG_GAME, game)
            val fragment = AboutGameBottomSheet()
            fragment.arguments = args
            return fragment
        }
    }
}
