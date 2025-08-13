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
import com.google.android.material.switchmaterial.SwitchMaterial
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.HomeNavigationDirections
import org.citra.citra_emu.R
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.utils.FileUtil
import org.citra.citra_emu.utils.GameIconUtils
import org.citra.citra_emu.viewmodel.GamesViewModel

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
        view.findViewById<TextView>(R.id.about_game_id).text = "ID: " + String.format("%016X", game.titleId)
        view.findViewById<TextView>(R.id.about_game_filename).text = "File: " + game.filename
        GameIconUtils.loadGameIcon(requireActivity(), game, view.findViewById(R.id.game_icon))

        val preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
        val autoLoadStateKey = "auto_load_state_${game.titleId}"
        val autoLoadStateSwitch = view.findViewById<SwitchMaterial>(R.id.auto_load_state_switch)
        autoLoadStateSwitch.isChecked = preferences.getBoolean(autoLoadStateKey, true)
        autoLoadStateSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean(autoLoadStateKey, isChecked).apply()
        }

        view.findViewById<MaterialButton>(R.id.about_game_play).setOnClickListener {
            val action = HomeNavigationDirections.actionGlobalEmulationActivity(game)
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(action)
        }

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

        // 移除直接在关于面板选择图标的入口，改为在“添加到主屏幕”流程中选择
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

        popup.setOnMenuItemClickListener { menuItem ->
            val uninstallAction: () -> Unit = {
                when (menuItem.itemId) {
                    R.id.game_context_uninstall -> CitraApplication.documentsTree.deleteDocument(dirs.gameDir)
                    R.id.game_context_uninstall_dlc -> FileUtil.deleteDocument(CitraApplication.documentsTree.folderUriHelper(dirs.dlcDir).toString())
                    R.id.game_context_uninstall_updates -> FileUtil.deleteDocument(CitraApplication.documentsTree.folderUriHelper(dirs.updatesDir).toString())
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


