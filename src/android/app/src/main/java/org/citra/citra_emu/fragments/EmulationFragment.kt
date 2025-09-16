// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import kotlin.math.roundToInt
import android.content.DialogInterface
import android.content.res.Configuration
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.Html
import android.text.TextWatcher
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.drawerlayout.widget.DrawerLayout.DrawerListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.fragment.navArgs
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.collectLatest

import kotlinx.coroutines.launch
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.EmulationNavigationDirections
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.activities.EmulationActivity
import org.citra.citra_emu.databinding.DialogCheckboxBinding
import org.citra.citra_emu.databinding.DialogSliderBinding
import org.citra.citra_emu.databinding.FragmentEmulationBinding
import org.citra.citra_emu.display.PortraitScreenLayout
import org.citra.citra_emu.display.ScreenAdjustmentUtil
import org.citra.citra_emu.display.ScreenLayout
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.SettingsViewModel
import org.citra.citra_emu.features.settings.ui.SettingsActivity
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.utils.DirectoryInitialization
import org.citra.citra_emu.utils.DirectoryInitialization.DirectoryInitializationState
import org.citra.citra_emu.utils.EmulationMenuSettings
import org.citra.citra_emu.utils.FileUtil
import org.citra.citra_emu.utils.GameHelper
import org.citra.citra_emu.utils.GameIconUtils
import org.citra.citra_emu.utils.EmulationLifecycleUtil
import org.citra.citra_emu.utils.Log
import org.citra.citra_emu.utils.ViewUtils
import org.citra.citra_emu.viewmodel.EmulationViewModel
import org.citra.citra_emu.overlay.HotCornerOverlay
import org.citra.citra_emu.utils.HotCornerSettings
import org.citra.citra_emu.utils.TurboHelper

class EmulationFragment : Fragment(), SurfaceHolder.Callback, Choreographer.FrameCallback {
    private val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    private lateinit var emulationState: EmulationState
    private var perfStatsUpdater: Runnable? = null

    private lateinit var emulationActivity: EmulationActivity

    private var _binding: FragmentEmulationBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<EmulationFragmentArgs>()

    private lateinit var game: Game
    private lateinit var screenAdjustmentUtil: ScreenAdjustmentUtil

    private val emulationViewModel: EmulationViewModel by activityViewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is EmulationActivity) {
            emulationActivity = context
            NativeLibrary.setEmulationActivity(context)
        } else {
            throw IllegalStateException("EmulationFragment must have EmulationActivity parent")
        }
    }

    /**
     * Initialize anything that doesn't depend on the layout / views in here.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = requireActivity().intent
        val intentUri: Uri? = intent.data
        val oldIntentInfo = Pair(
            intent.getStringExtra("SelectedGame"),
            intent.getStringExtra("SelectedTitle")
        )
        var intentGame: Game? = null
        if (intentUri != null) {
            intentGame = if (Game.extensions.contains(FileUtil.getExtension(intentUri))) {
                GameHelper.getGame(intentUri, isInstalled = false, addedToLibrary = false)
            } else {
                null
            }
        } else if (oldIntentInfo.first != null) {
            val gameUri = Uri.parse(oldIntentInfo.first)
            intentGame = if (Game.extensions.contains(FileUtil.getExtension(gameUri))) {
                GameHelper.getGame(gameUri, isInstalled = false, addedToLibrary = false)
            } else {
                null
            }
        }

        try {
            game = args.game ?: intentGame!!
        } catch (e: NullPointerException) {
            Toast.makeText(
                requireContext(),
                R.string.no_game_present,
                Toast.LENGTH_SHORT
            ).show()
            requireActivity().finish()
            return
        }

        // So this fragment doesn't restart on configuration changes; i.e. rotation.
        retainInstance = true
        emulationState = EmulationState(game.path)
        emulationActivity = requireActivity() as EmulationActivity
        screenAdjustmentUtil = ScreenAdjustmentUtil(requireContext(), requireActivity().windowManager, settingsViewModel.settings)
        EmulationLifecycleUtil.addShutdownHook(hook = { emulationState.stop() })
        EmulationLifecycleUtil.addPauseResumeHook(hook = { togglePauseAndSyncMenu() })
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEmulationBinding.inflate(inflater)
        return binding.root
    }

    // This is using the correct scope, lint is just acting up
    @SuppressLint("UnsafeRepeatOnLifecycleDetector")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (requireActivity().isFinishing) {
            return
        }

        binding.surfaceEmulation.holder.addCallback(this)
        binding.btnExportControls.setOnClickListener {
            exportOverlayLayoutToClipboard()
        }
        binding.doneControlConfig.setOnClickListener {
            binding.controlEditActions.visibility = View.GONE
            binding.surfaceInputOverlay.setIsInEditMode(false)
            // 恢复热区
            binding.hotCornerOverlay.visibility = View.VISIBLE
            binding.hotCornerOverlay.refresh()
        }

        // Setup hot corner overlay
        binding.hotCornerOverlay.apply {
            refresh()
            setOnActionListener(object : HotCornerOverlay.OnActionListener {
                override fun onHotCornerAction(action: HotCornerSettings.HotCornerAction) {
                    when (action) {
                        HotCornerSettings.HotCornerAction.PAUSE_RESUME -> togglePauseAndSyncMenu()
                        HotCornerSettings.HotCornerAction.TOGGLE_TURBO -> TurboHelper.setTurboEnabled(!TurboHelper.isTurboSpeedEnabled())
                        HotCornerSettings.HotCornerAction.QUICK_SAVE -> {
                            NativeLibrary.saveState(NativeLibrary.QUICKSAVE_SLOT)
                            Toast.makeText(requireContext(), getString(R.string.saving), Toast.LENGTH_SHORT).show()
                        }
                        HotCornerSettings.HotCornerAction.QUICK_LOAD -> {
                            val wasLoaded = NativeLibrary.loadStateIfAvailable(NativeLibrary.QUICKSAVE_SLOT)
                            val stringRes = if (wasLoaded) R.string.loading else R.string.quickload_not_found
                            Toast.makeText(requireContext(), getString(stringRes), Toast.LENGTH_SHORT).show()
                        }
                        HotCornerSettings.HotCornerAction.OPEN_MENU -> {
                            openDrawer()
                        }
                        HotCornerSettings.HotCornerAction.SWAP_SCREENS -> {
                            screenAdjustmentUtil.swapScreen()
                        }
                        HotCornerSettings.HotCornerAction.NONE -> {}
                    }
                }
            })
            setOnPressListener(object : HotCornerOverlay.OnPressListener {
                override fun onBottomCenterPress(isPressed: Boolean) {
                    setHotCornerHudVisible(isPressed)
                }
            })
        }

        // Show/hide the "Show FPS" overlay
        updateShowFpsOverlay()

        // Initialize border overlay
        initializeBorderOverlay()



        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.drawerLayout.addDrawerListener(object : DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                binding.surfaceInputOverlay.dispatchTouchEvent(
                    MotionEvent.obtain(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis() + 100,
                        MotionEvent.ACTION_UP,
                        0f,
                        0f,
                        0
                    )
                )
            }

            override fun onDrawerOpened(drawerView: View) {
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                binding.surfaceInputOverlay.isClickable = false
                binding.surfaceInputOverlay.isFocusable = false
                binding.surfaceInputOverlay.isFocusableInTouchMode = false
            }

            override fun onDrawerClosed(drawerView: View) {
                binding.drawerLayout.setDrawerLockMode(EmulationMenuSettings.drawerLockMode)
                binding.surfaceInputOverlay.isClickable = true
                binding.surfaceInputOverlay.isFocusable = true
                binding.surfaceInputOverlay.isFocusableInTouchMode = true
            }

            override fun onDrawerStateChanged(newState: Int) {
                // No op
            }
        })
        binding.inGameMenu.menu.findItem(R.id.menu_lock_drawer).apply {
            val titleId =
                if (EmulationMenuSettings.drawerLockMode == DrawerLayout.LOCK_MODE_LOCKED_CLOSED) {
                    R.string.unlock_drawer
                } else {
                    R.string.lock_drawer
                }
            val iconId =
                if (EmulationMenuSettings.drawerLockMode == DrawerLayout.LOCK_MODE_UNLOCKED) {
                    R.drawable.ic_unlocked
                } else {
                    R.drawable.ic_lock
                }

            title = getString(titleId)
            icon = ResourcesCompat.getDrawable(
                resources,
                iconId,
                requireContext().theme
            )
        }

        binding.inGameMenu.getHeaderView(0).apply {
            val titleView = findViewById<TextView>(R.id.text_game_title)
            val iconView = findViewById<ImageView>(R.id.game_icon)

            titleView.text = game.title

            GameIconUtils.loadGameIcon(requireActivity(), game, iconView)
        }

        binding.inGameMenu.setNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.menu_emulation_pause -> {
                    togglePauseAndSyncMenu()
                    true
                }

                R.id.menu_emulation_save_state -> {
                    showStateSubmenu(true)
                    true
                }

                R.id.menu_emulation_load_state -> {
                    showStateSubmenu(false)
                    true
                }

                R.id.menu_overlay_options -> {
                    showOverlayMenu()
                    true
                }

                R.id.menu_amiibo -> {
                    showAmiiboMenu()
                    true
                }

                R.id.menu_landscape_screen_layout -> {
                    showLandscapeScreenLayoutMenu()
                    true
                }

                R.id.menu_portrait_screen_layout -> {
                    showPortraitScreenLayoutMenu()
                    true
                }

                R.id.menu_swap_screens -> {
                    screenAdjustmentUtil.swapScreen()
                    true
                }

                R.id.menu_lock_drawer -> {
                    when (EmulationMenuSettings.drawerLockMode) {
                        DrawerLayout.LOCK_MODE_UNLOCKED -> {
                            EmulationMenuSettings.drawerLockMode =
                                DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                            it.title = resources.getString(R.string.unlock_drawer)
                            it.icon = ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_lock,
                                requireContext().theme
                            )
                        }

                        DrawerLayout.LOCK_MODE_LOCKED_CLOSED -> {
                            EmulationMenuSettings.drawerLockMode = DrawerLayout.LOCK_MODE_UNLOCKED
                            it.title = resources.getString(R.string.lock_drawer)
                            it.icon = ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_unlocked,
                                requireContext().theme
                            )
                        }
                    }
                    true
                }

                R.id.menu_cheats -> {
                    val action = EmulationNavigationDirections
                        .actionGlobalCheatsActivity(NativeLibrary.getRunningTitleId())
                    binding.root.findNavController().navigate(action)
                    true
                }

                R.id.menu_settings -> {
                    SettingsActivity.launch(
                        requireContext(),
                        SettingsFile.FILE_NAME_CONFIG,
                        ""
                    )

                    true
                }

                R.id.menu_exit -> {
                    NativeLibrary.pauseEmulation()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.emulation_close_game)
                        .setMessage(R.string.emulation_close_game_message)
                        .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                            // 如果开启了自动保存，则在退出前保存到自动保存槽位，完成后再关闭
                            if (BooleanSetting.AUTO_SAVE_ON_EXIT.boolean && NativeLibrary.isRunning()) {
                                try {
                                    val slotForAutoSave = NativeLibrary.AUTO_SAVE_SLOT
                                    // 记录保存前该槽位时间戳，用于判断完成
                                    val prevTime = try {
                                        NativeLibrary.getSavestateInfo()
                                            ?.firstOrNull { it.slot == slotForAutoSave }
                                            ?.time?.time ?: 0L
                                    } catch (_: Exception) { 0L }

                                    NativeLibrary.unPauseEmulation()
                                    NativeLibrary.saveState(slotForAutoSave)

                                    // 轮询等待保存完成（时间变更），最长等待 5 秒，避免阻塞与 ANR
                                    val start = SystemClock.uptimeMillis()
                                    val handler = Handler(Looper.getMainLooper())
                                    lateinit var checkRunnable: Runnable
                                    checkRunnable = Runnable {
                                        val now = SystemClock.uptimeMillis()
                                        val currentTime = try {
                                            NativeLibrary.getSavestateInfo()
                                                ?.firstOrNull { it.slot == slotForAutoSave }
                                                ?.time?.time ?: 0L
                                        } catch (_: Exception) { 0L }

                                        val done = currentTime > prevTime
                                        val timeout = now - start > 5000L
                                        if (done) {
                                            Toast.makeText(requireContext(), getString(R.string.game_saved), Toast.LENGTH_SHORT).show()
                                            EmulationLifecycleUtil.closeGame()
                                        } else if (timeout) {
                                            // 超时仍然关闭，避免卡住
                                            EmulationLifecycleUtil.closeGame()
                                        } else {
                                            handler.postDelayed(checkRunnable, 150L)
                                        }
                                    }
                                    handler.postDelayed(checkRunnable, 150L)
                                } catch (_: Exception) {
                                    EmulationLifecycleUtil.closeGame()
                                }
                            } else {
                                EmulationLifecycleUtil.closeGame()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int ->
                            NativeLibrary.unPauseEmulation()
                        }
                        .setOnCancelListener { NativeLibrary.unPauseEmulation() }
                        .show()
                    true
                }

                else -> true
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!emulationViewModel.emulationStarted.value) {
                        return
                    }

                    if (binding.drawerLayout.isOpen) {
                        binding.drawerLayout.close()
                    } else {
                        binding.drawerLayout.open()
                    }
                }
            }
        )

        GameIconUtils.loadGameIcon(requireActivity(), game, binding.loadingImage)
        binding.loadingTitle.text = game.title

        viewLifecycleOwner.lifecycleScope.apply {
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.shaderProgress.collectLatest {
                        if (it > 0 && it != emulationViewModel.totalShaders.value) {
                            binding.loadingProgressIndicator.isIndeterminate = false
                            binding.loadingProgressText.visibility = View.VISIBLE
                            binding.loadingProgressText.text = String.format(
                                "%d/%d",
                                emulationViewModel.shaderProgress.value,
                                emulationViewModel.totalShaders.value
                            )

                            if (it < binding.loadingProgressIndicator.max) {
                                binding.loadingProgressIndicator.progress = it
                            }
                        }

                        if (it == emulationViewModel.totalShaders.value) {
                            binding.loadingText.setText(R.string.loading)
                            binding.loadingProgressIndicator.isIndeterminate = true
                            binding.loadingProgressText.visibility = View.GONE
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.totalShaders.collectLatest {
                        binding.loadingProgressIndicator.max = it
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.shaderMessage.collectLatest {
                        if (it != "") {
                            binding.loadingText.text = it
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.emulationStarted.collectLatest { started ->
                        if (started) {
                            ViewUtils.showView(binding.surfaceInputOverlay)
                            // 存档菜单项现在始终可见，不再需要根据存档状态动态显示
                            binding.drawerLayout.setDrawerLockMode(EmulationMenuSettings.drawerLockMode)
                            
                            // Enable border overlay when game is truly loaded
                            binding.customBorderOverlay?.let { borderView ->
                                startBorderRefresh(borderView)
                            }
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.loadingOverlayVisible.collectLatest { visible ->
                        if (visible) {
                            ViewUtils.showView(binding.loadingOverlay)
                            ViewUtils.showView(binding.loadingIndicator)
                        } else {
                            ViewUtils.hideView(binding.loadingOverlay)
                            ViewUtils.hideView(binding.loadingIndicator)
                        }
                    }
                }
            }
        }

        setInsets()
    }

    private fun setHotCornerHudVisible(visible: Boolean) {
        if (visible) {
            updateHotCornerHud()
            binding.hudTimeText.visibility = View.VISIBLE
            binding.hudBatteryText.visibility = View.VISIBLE
        } else {
            binding.hudTimeText.visibility = View.GONE
            binding.hudBatteryText.visibility = View.GONE
        }
    }

    private fun updateHotCornerHud() {
        val timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        binding.hudTimeText.text = timeText
        val bm = requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        binding.hudBatteryText.text = String.format("%d%%", level)
        val orientation = resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape: white, 100% opacity
            binding.hudTimeText.setTextColor(android.graphics.Color.WHITE)
            binding.hudBatteryText.setTextColor(android.graphics.Color.WHITE)
            val outlineColor = android.graphics.Color.argb(128, 0, 0, 0)
            binding.hudTimeText.setShadowLayer(3f, 0f, 0f, outlineColor)
            binding.hudBatteryText.setShadowLayer(3f, 0f, 0f, outlineColor)
        } else {
            // Portrait: black, 50% opacity
            val semiBlack = android.graphics.Color.argb(128, 0, 0, 0)
            binding.hudTimeText.setTextColor(semiBlack)
            binding.hudBatteryText.setTextColor(semiBlack)
            // Remove shadow/outline in portrait
            binding.hudTimeText.setShadowLayer(0f, 0f, 0f, 0)
            binding.hudBatteryText.setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    fun isDrawerOpen(): Boolean {
        return binding.drawerLayout.isOpen
    }

    fun getCurrentGame(): Game {
        return game
    }

    fun openDrawer() {
        binding.drawerLayout.open()
    }

    fun startNewGame(newGame: Game) {
        // 停止当前仿真（若仍在运行）
        if (NativeLibrary.isRunning()) {
            try {
                emulationState.stop()
            } catch (_: Exception) {
            }
        }

        // 更新数据
        game = newGame
        emulationState = EmulationState(newGame.path)

        // 更新 UI 预览与标题
        if (_binding != null) {
            try {
                binding.inGameMenu.getHeaderView(0).apply {
                    findViewById<TextView>(R.id.text_game_title).text = newGame.title
                    val iconView = findViewById<ImageView>(R.id.game_icon)
                    GameIconUtils.loadGameIcon(requireActivity(), newGame, iconView)
                }
                GameIconUtils.loadGameIcon(requireActivity(), newGame, binding.loadingImage)
                binding.loadingTitle.text = newGame.title
            } catch (_: Exception) {
            }
        }

        // 启动新游戏
        if (DirectoryInitialization.areCitraDirectoriesReady()) {
            emulationState.run(false)
        } else {
            setupCitraDirectoriesThenStartEmulation()
        }
    }


    private fun togglePause() {
        if (emulationState.isPaused) {
            // 用户显式恢复
            emulationState.setKeepPausedRequested(false)
            emulationState.unpause()
            hidePauseIcon()
        } else {
            // 用户显式暂停
            emulationState.pause()
            emulationState.setKeepPausedRequested(true)
            showPauseIcon()
        }
    }

    private fun togglePauseAndSyncMenu() {
        val wasPaused = emulationState.isPaused
        togglePause()
        binding.inGameMenu.menu.findItem(R.id.menu_emulation_pause)?.let { menuItem ->
            if (wasPaused) {
                menuItem.title = resources.getString(R.string.pause_emulation)
                menuItem.icon = ResourcesCompat.getDrawable(
                    resources,
                    R.drawable.ic_pause,
                    requireContext().theme
                )
            } else {
                menuItem.title = resources.getString(R.string.resume_emulation)
                menuItem.icon = ResourcesCompat.getDrawable(
                    resources,
                    R.drawable.ic_play,
                    requireContext().theme
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh hot corners in case orientation or settings changed while paused
        binding.hotCornerOverlay.refresh()
        Choreographer.getInstance().postFrameCallback(this)
        if (NativeLibrary.isRunning()) {
            if (emulationState.isPaused && emulationState.isKeepPausedRequested) {
                // 保持用户的手动暂停，不自动恢复，只同步菜单为“继续”
                try {
                    // 为避免黑屏，渲染一帧后继续保持暂停
                    emulationState.presentFrameWhilePaused()
                } catch (_: Exception) { }
                showPauseIcon()
                binding.inGameMenu.menu.findItem(R.id.menu_emulation_pause)?.let { menuItem ->
                    menuItem.title = resources.getString(R.string.resume_emulation)
                    menuItem.icon = ResourcesCompat.getDrawable(
                        resources,
                        R.drawable.ic_play,
                        requireContext().theme
                    )
                }
            } else {
                // 非用户意图暂停（系统导致）才自动恢复
                NativeLibrary.unPauseEmulation()
                hidePauseIcon()
                binding.inGameMenu.menu.findItem(R.id.menu_emulation_pause)?.let { menuItem ->
                    menuItem.title = resources.getString(R.string.pause_emulation)
                    menuItem.icon = ResourcesCompat.getDrawable(
                        resources,
                        R.drawable.ic_pause,
                        requireContext().theme
                    )
                }
            }
            return
        }

        if (DirectoryInitialization.areCitraDirectoriesReady()) {
            emulationState.run(emulationActivity.isActivityRecreated)
        } else {
            setupCitraDirectoriesThenStartEmulation()
        }
    }

    override fun onPause() {
        if (NativeLibrary.isRunning()) {
            emulationState.pause()
        }
        Choreographer.getInstance().removeFrameCallback(this)
        super.onPause()
    }

    override fun onDetach() {
        NativeLibrary.clearEmulationActivity()
        super.onDetach()
    }

    private fun setupCitraDirectoriesThenStartEmulation() {
        val directoryInitializationState = DirectoryInitialization.start()
        if (directoryInitializationState ===
            DirectoryInitializationState.CITRA_DIRECTORIES_INITIALIZED
        ) {
            emulationState.run(emulationActivity.isActivityRecreated)
        } else if (directoryInitializationState ===
            DirectoryInitializationState.EXTERNAL_STORAGE_PERMISSION_NEEDED
        ) {
            Toast.makeText(context, R.string.write_permission_needed, Toast.LENGTH_SHORT)
                .show()
        } else if (directoryInitializationState ===
            DirectoryInitializationState.CANT_FIND_EXTERNAL_STORAGE
        ) {
            Toast.makeText(
                context,
                R.string.external_storage_not_mounted,
                Toast.LENGTH_SHORT
            ).show()
        }
    }



    private fun showStateSubmenu(isSaving: Boolean) {

        val savestates = NativeLibrary.getSavestateInfo()

        // 找到最新的存档（时间最新的）
        val latestSavestate = savestates?.maxByOrNull { it.time?.time ?: 0L }

        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_emulation_save_state)
        )

        // 自定义顺序：Quick Save -> Auto Save -> Slot 1..(AUTO_SAVE_SLOT-1)
        val slotOrder = mutableListOf<Int>().apply {
            add(NativeLibrary.QUICKSAVE_SLOT)
            if (NativeLibrary.AUTO_SAVE_SLOT != NativeLibrary.QUICKSAVE_SLOT) add(NativeLibrary.AUTO_SAVE_SLOT)
            for (i in 1 until NativeLibrary.AUTO_SAVE_SLOT) add(i)
        }

        val slotToMenuItem = HashMap<Int, android.view.MenuItem>()

        popupMenu.menu.apply {
            for (slot in slotOrder) {
                val enableClick = isSaving
                val text = when (slot) {
                    NativeLibrary.QUICKSAVE_SLOT -> getString(R.string.emulation_quicksave_slot)
                    NativeLibrary.AUTO_SAVE_SLOT -> getString(R.string.emulation_autosave_slot)
                    else -> getString(R.string.emulation_empty_state_slot, slot)
                }

                val item = add(text).setEnabled(enableClick)
                slotToMenuItem[slot] = item
                item.setOnMenuItemClickListener {
                    if (isSaving) {
                        NativeLibrary.saveState(slot)
                        Toast.makeText(context, getString(R.string.saving), Toast.LENGTH_SHORT).show()
                    } else {
                        NativeLibrary.loadState(slot)
                        binding.drawerLayout.close()
                        Toast.makeText(context, getString(R.string.loading), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }
        }

        // 覆盖已有存档的标题，并在加载模式下启用对应菜单项
        savestates?.forEach {
            val text = when (it.slot) {
                NativeLibrary.QUICKSAVE_SLOT -> getString(R.string.emulation_occupied_quicksave_slot, it.time)
                NativeLibrary.AUTO_SAVE_SLOT -> getString(R.string.emulation_occupied_autosave_slot, it.time)
                else -> getString(R.string.emulation_occupied_state_slot, it.slot, it.time)
            }
            val menuItem = slotToMenuItem[it.slot]
            if (menuItem != null) {
                menuItem.setTitle(text)
                if (!isSaving) menuItem.setEnabled(true)

                // 如果这是最新的存档，设置高亮颜色
                if (it == latestSavestate) {
                    menuItem.setTitle(Html.fromHtml("<font color='#1A4DAB'>$text</font>", Html.FROM_HTML_MODE_LEGACY))
                }
            }
        }

        popupMenu.show()
    }



    private fun displaySavestateWarning() {
        if (preferences.getBoolean("savestateWarningShown", false)) {
            return
        }

        val dialogCheckboxBinding = DialogCheckboxBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.savestates)
            .setMessage(R.string.savestate_warning_message)
            .setView(dialogCheckboxBinding.root)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                preferences.edit()
                    .putBoolean("savestateWarningShown", dialogCheckboxBinding.checkBox.isChecked)
                    .apply()
            }
            .show()
    }

    private fun showOverlayMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_overlay_options)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_overlay_options, popupMenu.menu)

        popupMenu.menu.apply {
            findItem(R.id.menu_show_overlay).isChecked = EmulationMenuSettings.showOverlay
            findItem(R.id.menu_show_fps).isChecked = EmulationMenuSettings.showFps
            findItem(R.id.menu_haptic_feedback).isChecked = EmulationMenuSettings.hapticFeedback
            findItem(R.id.menu_emulation_joystick_rel_center).isChecked =
                EmulationMenuSettings.joystickRelCenter
            findItem(R.id.menu_emulation_dpad_slide_enable).isChecked =
                EmulationMenuSettings.dpadSlide
        }

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_show_overlay -> {
                    EmulationMenuSettings.showOverlay = !EmulationMenuSettings.showOverlay
                    binding.surfaceInputOverlay.refreshControls()
                    true
                }

                R.id.menu_show_fps -> {
                    EmulationMenuSettings.showFps = !EmulationMenuSettings.showFps
                    updateShowFpsOverlay()
                    true
                }

                R.id.menu_haptic_feedback -> {
                    EmulationMenuSettings.hapticFeedback = !EmulationMenuSettings.hapticFeedback
                    updateShowFpsOverlay()
                    true
                }

                R.id.menu_emulation_edit_layout -> {
                    editControlsPlacement()
                    binding.drawerLayout.close()
                    true
                }

                R.id.menu_emulation_toggle_controls -> {
                    showToggleControlsDialog()
                    true
                }

                R.id.menu_emulation_adjust_scale_reset_all -> {
                    resetAllScales()
                    true
                }

                R.id.menu_emulation_adjust_scale -> {
                    showAdjustScaleDialog("controlScale")
                    true
                }

                R.id.menu_emulation_adjust_scale_button_a -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_A)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_b -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_B)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_x -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_X)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_y -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_Y)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_l -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.TRIGGER_L)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_r -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.TRIGGER_R)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_zl -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_ZL)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_zr -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_ZR)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_start -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_START)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_select -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_SELECT)
                    true
                }

                R.id.menu_emulation_adjust_scale_controller_dpad -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.DPAD)
                    true
                }

                R.id.menu_emulation_adjust_scale_controller_circlepad -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.STICK_LEFT)
                    true
                }

                R.id.menu_emulation_adjust_scale_controller_c -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.STICK_C)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_home -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_HOME)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_swap -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_SWAP)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_quick_save -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_QUICK_SAVE)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_quick_load -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_QUICK_LOAD)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_menu -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_MENU)
                    true
                }

                R.id.menu_emulation_adjust_opacity -> {
                    showAdjustOpacityDialog()
                    true
                }

                R.id.menu_emulation_hot_corner_portrait_bl -> {
                    showHotCornerDialog(
                        Configuration.ORIENTATION_PORTRAIT,
                        HotCornerSettings.HotCornerPosition.BOTTOM_LEFT,
                        getString(R.string.emulation_hot_corner_portrait_bl)
                    )
                    true
                }

                R.id.menu_emulation_hot_corner_portrait_br -> {
                    showHotCornerDialog(
                        Configuration.ORIENTATION_PORTRAIT,
                        HotCornerSettings.HotCornerPosition.BOTTOM_RIGHT,
                        getString(R.string.emulation_hot_corner_portrait_br)
                    )
                    true
                }

                R.id.menu_emulation_hot_corner_landscape_bl -> {
                    showHotCornerDialog(
                        Configuration.ORIENTATION_LANDSCAPE,
                        HotCornerSettings.HotCornerPosition.BOTTOM_LEFT,
                        getString(R.string.emulation_hot_corner_landscape_bl)
                    )
                    true
                }

                R.id.menu_emulation_hot_corner_landscape_br -> {
                    showHotCornerDialog(
                        Configuration.ORIENTATION_LANDSCAPE,
                        HotCornerSettings.HotCornerPosition.BOTTOM_RIGHT,
                        getString(R.string.emulation_hot_corner_landscape_br)
                    )
                    true
                }

                R.id.menu_emulation_hot_corner_bottom_center -> {
                    showBottomCenterHotCornerDialog()
                    true
                }

                R.id.menu_emulation_joystick_rel_center -> {
                    EmulationMenuSettings.joystickRelCenter =
                        !EmulationMenuSettings.joystickRelCenter
                    true
                }

                R.id.menu_emulation_dpad_slide_enable -> {
                    EmulationMenuSettings.dpadSlide = !EmulationMenuSettings.dpadSlide
                    true
                }

                R.id.menu_emulation_reset_overlay -> {
                    showResetOverlayDialog()
                    true
                }

                else -> true
            }
        }

        popupMenu.show()
    }

    // 实现包含选项与保存/取消的 UI，并写入设置后刷新热区
    private fun showHotCornerDialog(
        orientation: Int,
        position: HotCornerSettings.HotCornerPosition,
        title: String
    ) {
        val items = arrayOf(
            getString(R.string.emulation_hot_corner_action_none),
            getString(R.string.emulation_hot_corner_action_pause),
            getString(R.string.emulation_hot_corner_action_turbo),
            getString(R.string.emulation_hot_corner_action_quicksave),
            getString(R.string.emulation_hot_corner_action_quickload),
            getString(R.string.emulation_hot_corner_action_menu),
            getString(R.string.emulation_hot_corner_action_swap)
        )
        val current = HotCornerSettings.getAction(orientation, position)
        var selectedIndex = when (current) {
            HotCornerSettings.HotCornerAction.NONE -> 0
            HotCornerSettings.HotCornerAction.PAUSE_RESUME -> 1
            HotCornerSettings.HotCornerAction.TOGGLE_TURBO -> 2
            HotCornerSettings.HotCornerAction.QUICK_SAVE -> 3
            HotCornerSettings.HotCornerAction.QUICK_LOAD -> 4
            HotCornerSettings.HotCornerAction.OPEN_MENU -> 5
            HotCornerSettings.HotCornerAction.SWAP_SCREENS -> 6
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(R.string.save) { dialog, _ ->
                val action = when (selectedIndex) {
                    0 -> HotCornerSettings.HotCornerAction.NONE
                    1 -> HotCornerSettings.HotCornerAction.PAUSE_RESUME
                    2 -> HotCornerSettings.HotCornerAction.TOGGLE_TURBO
                    3 -> HotCornerSettings.HotCornerAction.QUICK_SAVE
                    4 -> HotCornerSettings.HotCornerAction.QUICK_LOAD
                    5 -> HotCornerSettings.HotCornerAction.OPEN_MENU
                    6 -> HotCornerSettings.HotCornerAction.SWAP_SCREENS
                    else -> HotCornerSettings.HotCornerAction.NONE
                }
                HotCornerSettings.setAction(orientation, position, action)
                binding.hotCornerOverlay.refresh()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showBottomCenterHotCornerDialog() {
        val items = arrayOf(
            getString(R.string.emulation_hot_corner_bottom_center_option_press_to_show_time_battery),
            getString(R.string.emulation_hot_corner_bottom_center_option_off)
        )
        val orientation = resources.configuration.orientation
        val current = HotCornerSettings.getBottomCenterMode(orientation)
        var selectedIndex = if (current == HotCornerSettings.BottomCenterMode.PRESS_TO_SHOW_HUD) 0 else 1

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emulation_hot_corner_bottom_center_dialog_title)
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(R.string.save) { dialog, _ ->
                val mode = if (selectedIndex == 0) HotCornerSettings.BottomCenterMode.PRESS_TO_SHOW_HUD else HotCornerSettings.BottomCenterMode.OFF
                HotCornerSettings.setBottomCenterMode(orientation, mode)
                binding.hotCornerOverlay.refresh()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showAmiiboMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_amiibo)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_amiibo_options, popupMenu.menu)

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_emulation_amiibo_load -> {
                    emulationActivity.openFileLauncher.launch(false)
                    true
                }

                R.id.menu_emulation_amiibo_remove -> {
                    NativeLibrary.removeAmiibo()
                    true
                }

                else -> true
            }
        }

        popupMenu.show()
    }

    private fun showLandscapeScreenLayoutMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_landscape_screen_layout)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_landscape_screen_layout, popupMenu.menu)

        val layoutOptionMenuItem = when (IntSetting.SCREEN_LAYOUT.int) {
            ScreenLayout.ORIGINAL.int ->
                R.id.menu_screen_layout_original

            ScreenLayout.SINGLE_SCREEN.int ->
                R.id.menu_screen_layout_single

            ScreenLayout.SIDE_SCREEN.int ->
                R.id.menu_screen_layout_sidebyside

            ScreenLayout.HYBRID_SCREEN.int ->
                R.id.menu_screen_layout_hybrid

            ScreenLayout.CUSTOM_LAYOUT.int ->
                R.id.menu_screen_layout_custom

            else -> R.id.menu_screen_layout_largescreen
        }
        popupMenu.menu.findItem(layoutOptionMenuItem).setChecked(true)

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_screen_layout_largescreen -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.LARGE_SCREEN.int)
                    true
                }

                R.id.menu_screen_layout_single -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.SINGLE_SCREEN.int)
                    true
                }

                R.id.menu_screen_layout_sidebyside -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.SIDE_SCREEN.int)
                    true
                }

                R.id.menu_screen_layout_hybrid -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.HYBRID_SCREEN.int)
                    true
                }

                R.id.menu_screen_layout_original -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.ORIGINAL.int)
                    true
                }

                R.id.menu_screen_layout_custom -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.emulation_adjust_custom_layout,
                        Toast.LENGTH_LONG
                    ).show()
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.CUSTOM_LAYOUT.int)
                    true
                }

                else -> true
            }
        }

        popupMenu.show()
    }

    private fun showPortraitScreenLayoutMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_portrait_screen_layout)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_portrait_screen_layout, popupMenu.menu)

        val layoutOptionMenuItem = when (IntSetting.PORTRAIT_SCREEN_LAYOUT.int) {
            PortraitScreenLayout.TOP_FULL_WIDTH.int ->
                R.id.menu_portrait_layout_top_full
            PortraitScreenLayout.ORIGINAL.int ->
                R.id.menu_portrait_layout_original
            PortraitScreenLayout.CUSTOM_PORTRAIT_LAYOUT.int ->
                R.id.menu_portrait_layout_custom
            else ->
                R.id.menu_portrait_layout_top_full

        }

        popupMenu.menu.findItem(layoutOptionMenuItem).setChecked(true)

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_portrait_layout_top_full -> {
                    screenAdjustmentUtil.changePortraitOrientation(PortraitScreenLayout.TOP_FULL_WIDTH.int)
                    true
                }

                R.id.menu_portrait_layout_original -> {
                    screenAdjustmentUtil.changePortraitOrientation(PortraitScreenLayout.ORIGINAL.int)
                    true
                }

                R.id.menu_portrait_layout_custom -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.emulation_adjust_custom_layout,
                        Toast.LENGTH_LONG
                    ).show()
                    screenAdjustmentUtil.changePortraitOrientation(PortraitScreenLayout.CUSTOM_PORTRAIT_LAYOUT.int)
                    true
                }

                else -> true
            }
        }

        popupMenu.show()
    }

    private fun editControlsPlacement() {
        if (binding.surfaceInputOverlay.isInEditMode) {
            binding.controlEditActions.visibility = View.GONE
            binding.surfaceInputOverlay.setIsInEditMode(false)
            // 恢复热区
            binding.hotCornerOverlay.visibility = View.VISIBLE
            binding.hotCornerOverlay.refresh()
        } else {
            binding.controlEditActions.visibility = View.VISIBLE
            binding.surfaceInputOverlay.setIsInEditMode(true)
            // 进入编辑模式时隐藏所有热区，避免拦截触控
            binding.hotCornerOverlay.visibility = View.GONE
            binding.hotCornerOverlay.refresh()
        }
    }

    private fun exportOverlayLayoutToClipboard() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val orientationSuffix = if (isPortrait) "-Portrait" else ""

        fun xyPctOf(idRaw: Int, portraitMode: Boolean): Pair<Int, Int> {
            val id = if (idRaw == NativeLibrary.ButtonType.DPAD) NativeLibrary.ButtonType.DPAD_UP else idRaw
            val suffix = if (portraitMode) "-Portrait" else ""
            val xPx = prefs.getFloat("$id$suffix-X", 0f)
            val yPx = prefs.getFloat("$id$suffix-Y", 0f)
            val display = requireActivity().windowManager.defaultDisplay
            val out = android.util.DisplayMetrics()
            display.getMetrics(out)
            var w = out.widthPixels
            var h = out.heightPixels
            if (portraitMode && h < w) {
                val t = w; w = h; h = t
            } else if (!portraitMode && w < h) {
                val t = w; w = h; h = t
            }
            val xPct = ((xPx / w) * 1000f).roundToInt()
            val yPct = ((yPx / h) * 1000f).roundToInt()
            return xPct to yPct
        }

        val ids = listOf(
            NativeLibrary.ButtonType.BUTTON_A,
            NativeLibrary.ButtonType.BUTTON_B,
            NativeLibrary.ButtonType.BUTTON_X,
            NativeLibrary.ButtonType.BUTTON_Y,
            NativeLibrary.ButtonType.TRIGGER_L,
            NativeLibrary.ButtonType.TRIGGER_R,
            NativeLibrary.ButtonType.BUTTON_ZL,
            NativeLibrary.ButtonType.BUTTON_ZR,
            NativeLibrary.ButtonType.BUTTON_START,
            NativeLibrary.ButtonType.BUTTON_SELECT,
            NativeLibrary.ButtonType.DPAD,
            NativeLibrary.ButtonType.STICK_LEFT,
            NativeLibrary.ButtonType.STICK_C,
            NativeLibrary.ButtonType.BUTTON_HOME,
            NativeLibrary.ButtonType.BUTTON_SWAP,
            NativeLibrary.ButtonType.BUTTON_TURBO,
            NativeLibrary.ButtonType.BUTTON_QUICK_SAVE,
            NativeLibrary.ButtonType.BUTTON_QUICK_LOAD,
            NativeLibrary.ButtonType.BUTTON_MENU,
        )

        val globalScale = prefs.getInt("controlScale", 50)
        val opacity = prefs.getInt("controlOpacity", 50)

        val sb = StringBuilder()
        sb.append("globalScale=$globalScale\n")
        sb.append("opacity=$opacity\n")

        sb.append("[landscape]\n")
        ids.forEach { id ->
            val (x, y) = xyPctOf(id, false)
            val perButtonScale = prefs.getInt("controlScale-$id", 50)
            sb.append("$id: x=$x, y=$y, scale=$perButtonScale\n")
        }

        sb.append("[portrait]\n")
        ids.forEach { id ->
            val (x, y) = xyPctOf(id, true)
            val perButtonScale = prefs.getInt("controlScale-$id", 50)
            sb.append("$id: x=$x, y=$y, scale=$perButtonScale\n")
        }

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("overlay", sb.toString()))
        Toast.makeText(requireContext(), getString(R.string.export_controls_copied), Toast.LENGTH_SHORT).show()
    }

    private fun showToggleControlsDialog() {
        val editor = preferences.edit()
        val enabledButtons = BooleanArray(19)
        enabledButtons.forEachIndexed { i: Int, _: Boolean ->
            // Buttons that are disabled by default
            var defaultValue = true
            when (i) {
                6, 7, 12, 13, 14, 15, 16, 17, 18 -> defaultValue = false
            }
            enabledButtons[i] = preferences.getBoolean("buttonToggle$i", defaultValue)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emulation_toggle_controls)
            .setMultiChoiceItems(
                R.array.n3dsButtons, enabledButtons
            ) { _: DialogInterface?, indexSelected: Int, isChecked: Boolean ->
                editor.putBoolean("buttonToggle$indexSelected", isChecked)
            }
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                editor.apply()
                binding.surfaceInputOverlay.refreshControls()
            }
            .show()
    }

    private fun showAdjustScaleDialog(target: String) {
        val sliderBinding = DialogSliderBinding.inflate(layoutInflater)

        sliderBinding.apply {
            slider.valueTo = 150f
            slider.valueFrom = 0f
            slider.value = preferences.getInt(target, 50).toFloat()
            textValue.setText((slider.value + 50).toInt().toString())
            textValue.addTextChangedListener( object : TextWatcher {
                override fun afterTextChanged(s: Editable) {
                    val value = s.toString().toIntOrNull()
                    if (value == null || value < 50 || value > 150) {
                        textInput.error = "Inappropriate Value"
                    } else {
                        textInput.error = null
                        slider.value = value.toFloat() - 50
                    }
                }
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            })
            slider.addOnChangeListener(
                Slider.OnChangeListener { slider: Slider, progress: Float, _: Boolean ->
                    if (textValue.text.toString() != (slider.value + 50).toInt().toString()) {
                        textValue.setText((slider.value + 50).toInt().toString())
                        textValue.setSelection(textValue.length())
                        setControlScale(slider.value.toInt(), target)
                    }

                })
            textInput.suffixText = "%"
        }
        val previousProgress = sliderBinding.slider.value.toInt()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emulation_control_scale)
            .setView(sliderBinding.root)
            .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int ->
                setControlScale(previousProgress, target)
            }
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                setControlScale(sliderBinding.slider.value.toInt(), target)
            }
            .setNeutralButton(R.string.slider_default) { _: DialogInterface?, _: Int ->
                setControlScale(50, target)
            }
            .show()
    }

    private fun showAdjustOpacityDialog() {
        val sliderBinding = DialogSliderBinding.inflate(layoutInflater)

        sliderBinding.apply {
            slider.valueFrom = 0f
            slider.valueTo = 100f
            slider.value = preferences.getInt("controlOpacity", 50).toFloat()
            textValue.setText(slider.value.toInt().toString())

            textValue.addTextChangedListener( object : TextWatcher {
                override fun afterTextChanged(s: Editable) {
                    val value = s.toString().toIntOrNull()
                    if (value == null || value < slider.valueFrom || value > slider.valueTo) {
                        textInput.error = "Inappropriate Value"
                    } else {
                        textInput.error = null
                        slider.value = value.toFloat()
                    }
                }
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            })


            slider.addOnChangeListener { _: Slider, value: Float, _: Boolean ->

                if (textValue.text.toString() != slider.value.toInt().toString()) {
                        textValue.setText(slider.value.toInt().toString())
                        textValue.setSelection(textValue.length())
                        setControlOpacity(slider.value.toInt())
                    }
                }

            textInput.suffixText = "%"
        }
        val previousProgress = sliderBinding.slider.value.toInt()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emulation_control_opacity)
            .setView(sliderBinding.root)
            .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int ->
                setControlOpacity(previousProgress)
            }
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                setControlOpacity(sliderBinding.slider.value.toInt())
            }
            .setNeutralButton(R.string.slider_default) { _: DialogInterface?, _: Int ->
                setControlOpacity(50)
            }
            .show()
    }

    private fun setControlScale(scale: Int, target: String) {
        preferences.edit()
            .putInt(target, scale)
            .apply()
        binding.surfaceInputOverlay.refreshControls()
    }

    private fun resetScale(target: String) {
        preferences.edit().putInt(
            target,
            50
        ).apply()
    }

    private fun resetAllScales() {
        resetScale("controlScale")
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_A)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_B)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_X)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_Y)
        resetScale("controlScale-" + NativeLibrary.ButtonType.TRIGGER_L)
        resetScale("controlScale-" + NativeLibrary.ButtonType.TRIGGER_R)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_ZL)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_ZR)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_START)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_SELECT)
        resetScale("controlScale-" + NativeLibrary.ButtonType.DPAD)
        resetScale("controlScale-" + NativeLibrary.ButtonType.STICK_LEFT)
        resetScale("controlScale-" + NativeLibrary.ButtonType.STICK_C)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_HOME)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_SWAP)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_QUICK_SAVE)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_QUICK_LOAD)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_MENU)
        binding.surfaceInputOverlay.refreshControls()
    }

    private fun setControlOpacity(opacity: Int) {
        preferences.edit()
            .putInt("controlOpacity", opacity)
            .apply()
        binding.surfaceInputOverlay.refreshControls()
    }

    private fun showResetOverlayDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.emulation_touch_overlay_reset))
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                resetInputOverlay()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resetInputOverlay() {
        resetAllScales()
        preferences.edit()
            .putInt("controlOpacity", 100)
            .apply()

        val editor = preferences.edit()
        for (i in 0 until 19) {
            var defaultValue = true
            when (i) {
                // Disabled by default: ZL, ZR, Stick C, Home, Swap, Turbo
                // Keep Quick Save/Load/Menu (16/17/18) enabled by default
                6, 7, 12, 13, 14, 15 -> defaultValue = false
            }
            editor.putBoolean("buttonToggle$i", defaultValue)
        }
        editor.apply()

        binding.surfaceInputOverlay.resetButtonPlacement()
    }

    fun updateShowFpsOverlay() {
        if (EmulationMenuSettings.showFps) {
            val SYSTEM_FPS = 0
            val FPS = 1
            val FRAMETIME = 2
            val SPEED = 3
            perfStatsUpdater = Runnable {
                val perfStats = NativeLibrary.getPerfStats()
                if (perfStats[FPS] > 0) {
                    binding.showFpsText.text = String.format(
                        "FPS: %d Speed: %d%% FT: %.2fms",
                        (perfStats[FPS] + 0.5).toInt(),
                        (perfStats[SPEED] * 100.0 + 0.5).toInt(),
                        (perfStats[FRAMETIME] * 1000.0f).toFloat()
                    )
                }
                perfStatsUpdateHandler.postDelayed(perfStatsUpdater!!, 3000)
            }
            perfStatsUpdateHandler.post(perfStatsUpdater!!)
            binding.showFpsText.visibility = View.VISIBLE
        } else {
            if (perfStatsUpdater != null) {
                perfStatsUpdateHandler.removeCallbacks(perfStatsUpdater!!)
            }
            binding.showFpsText.visibility = View.GONE
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // We purposely don't do anything here.
        // All work is done in surfaceChanged, which we are guaranteed to get even for surface creation.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.debug("[EmulationFragment] Surface changed. Resolution: " + width + "x" + height)
        emulationState.newSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        emulationState.clearSurface()
    }

    override fun doFrame(frameTimeNanos: Long) {
        Choreographer.getInstance().postFrameCallback(this)
        NativeLibrary.doFrame()
    }

    private fun setInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.inGameMenu
        ) { v: View, windowInsets: WindowInsetsCompat ->
            val cutInsets: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            var left = 0
            var right = 0
            if (ViewCompat.getLayoutDirection(v) == ViewCompat.LAYOUT_DIRECTION_LTR) {
                left = cutInsets.left
            } else {
                right = cutInsets.right
            }

            v.setPadding(left, cutInsets.top, right, 0)

            // Ensure FPS text doesn't get cut off by rounded display corners
            val sidePadding = resources.getDimensionPixelSize(R.dimen.spacing_large)
            if (cutInsets.left == 0) {
                binding.showFpsText.setPadding(
                    sidePadding,
                    cutInsets.top,
                    cutInsets.right,
                    cutInsets.bottom
                )
            } else {
                binding.showFpsText.setPadding(
                    cutInsets.left,
                    cutInsets.top,
                    cutInsets.right,
                    cutInsets.bottom
                )
            }
            windowInsets
        }
    }

    private fun showPauseIcon() {
        if (_binding == null) return
        try {
            positionPauseIconOnTopScreen()
            binding.pauseIconOverlay.visibility = View.VISIBLE
            // 降低底层画面亮度，突出图标
            binding.surfaceEmulation.alpha = 0.75f
        } catch (_: Exception) { }
    }

    private fun hidePauseIcon() {
        if (_binding == null) return
        try {
            binding.pauseIconOverlay.visibility = View.GONE
            binding.surfaceEmulation.alpha = 1f
        } catch (_: Exception) { }
    }

    private fun positionPauseIconOnTopScreen() {
        if (_binding == null) return
        val layout = try { NativeLibrary.getScreenLayout() } catch (_: Exception) { null }
        if (layout == null || layout.size < 8) {
            // 退化：大致置于屏幕上半区中央
            binding.pauseIconOverlay.post {
                val parent = binding.root as View
                val centerX = parent.width / 2f
                val approxCenterY = parent.height / 4f
                val halfW = binding.pauseIconOverlay.width / 2f
                val halfH = binding.pauseIconOverlay.height / 2f
                binding.pauseIconOverlay.x = centerX - halfW
                binding.pauseIconOverlay.y = approxCenterY - halfH
            }
            return
        }

        // 布局数组: [tlx, tly, trx, try, blx, bly, brx, bry]
        val topLeftX = layout[0]
        val topLeftY = layout[1]
        val topRightX = layout[2]
        val topRightY = layout[3]

        val centerX = (topLeftX + topRightX) / 2f
        val centerY = (topLeftY + topRightY) / 2f

        binding.pauseIconOverlay.post {
            val halfW = binding.pauseIconOverlay.width / 2f
            val halfH = binding.pauseIconOverlay.height / 2f
            binding.pauseIconOverlay.x = centerX - halfW
            binding.pauseIconOverlay.y = centerY - halfH
        }
    }

    private class EmulationState(private val gamePath: String) {
        private var state: State
        private var surface: Surface? = null

        init {
            // Starting state is stopped.
            state = State.STOPPED
        }

        @get:Synchronized
        val isStopped: Boolean
            get() = state == State.STOPPED

        @get:Synchronized
        val isPaused: Boolean
            // Getters for the current state
            get() = state == State.PAUSED

        @get:Synchronized
        val isRunning: Boolean
            get() = state == State.RUNNING

        @Synchronized
        fun stop() {
            if (state != State.STOPPED) {
                Log.debug("[EmulationFragment] Stopping emulation.")
                state = State.STOPPED
                NativeLibrary.stopEmulation()
            } else {
                Log.warning("[EmulationFragment] Stop called while already stopped.")
            }
        }

        // State changing methods
        @Synchronized
        fun pause() {
            if (state != State.PAUSED) {
                state = State.PAUSED
                Log.debug("[EmulationFragment] Pausing emulation.")

                // Release the surface before pausing, since emulation has to be running for that.
                NativeLibrary.surfaceDestroyed()
                NativeLibrary.pauseEmulation()
            } else {
                Log.warning("[EmulationFragment] Pause called while already paused.")
            }
        }

        @Synchronized
        fun unpause() {
            if (state != State.RUNNING) {
                state = State.RUNNING
                Log.debug("[EmulationFragment] Unpausing emulation.")

                NativeLibrary.unPauseEmulation()
            } else {
                Log.warning("[EmulationFragment] Unpause called while already running.")
            }
        }

        @Synchronized
        fun run(isActivityRecreated: Boolean) {
            if (isActivityRecreated) {
                if (NativeLibrary.isRunning()) {
                    state = State.PAUSED
                }
            } else {
                Log.debug("[EmulationFragment] activity resumed or fresh start")
            }

            // If the surface is set, run now. Otherwise, wait for it to get set.
            if (surface != null) {
                // Apply per-game graphics API override before starting the run
                tryApplyPerGameGraphicsApiOverride()
                runWithValidSurface()
            }
        }

        // Surface callbacks
        @Synchronized
        fun newSurface(surface: Surface?) {
            this.surface = surface
            if (this.surface != null) {
                runWithValidSurface()
            }
        }

        @Synchronized
        fun clearSurface() {
            if (surface == null) {
                Log.warning("[EmulationFragment] clearSurface called, but surface already null.")
            } else {
                surface = null
                Log.debug("[EmulationFragment] Surface destroyed.")
                when (state) {
                    State.RUNNING -> {
                        NativeLibrary.surfaceDestroyed()
                        state = State.PAUSED
                    }

                    State.PAUSED -> {
                        Log.warning("[EmulationFragment] Surface cleared while emulation paused.")
                    }

                    else -> {
                        Log.warning("[EmulationFragment] Surface cleared while emulation stopped.")
                    }
                }
            }
        }

        private fun runWithValidSurface() {
            NativeLibrary.surfaceChanged(surface!!)
            when (state) {
                State.STOPPED -> {
                    Thread({
                        Log.debug("[EmulationFragment] Starting emulation thread.")
                        // Apply per-game graphics API override right before run as an extra safety
                        tryApplyPerGameGraphicsApiOverride()
                        NativeLibrary.run(gamePath)
                    }, "NativeEmulation").start()
                }

                State.PAUSED -> {
                    if (keepPausedRequested) {
                        Log.debug("[EmulationFragment] Surface ready, keep paused as requested by user.")
                        // 为避免黑屏，渲染一帧以附着内容后继续保持暂停
                        try {
                            presentFrameWhilePaused()
                        } catch (_: Exception) { }
                    } else {
                        Log.debug("[EmulationFragment] Resuming emulation.")
                        NativeLibrary.unPauseEmulation()
                    }
                }

                else -> {
                    Log.debug("[EmulationFragment] Bug, run called while already running.")
                }
            }
            // 仅当不是“用户请求保持暂停”时，才进入 RUNNING 状态
            if (!(state == State.PAUSED && keepPausedRequested)) {
                state = State.RUNNING
            }
        }

        private enum class State {
            STOPPED,
            RUNNING,
            PAUSED
        }

        // 记录用户是否明确要求保持暂停（用于返回前台/Surface 重建时不自动恢复）
        private var keepPausedRequested: Boolean = false

        @get:Synchronized
        val isKeepPausedRequested: Boolean
            get() = keepPausedRequested

        @Synchronized
        fun setKeepPausedRequested(requested: Boolean) {
            keepPausedRequested = requested
        }

        @Synchronized
        fun presentFrameWhilePaused() {
            if (state != State.PAUSED) return
            if (surface == null) return
            try {
                // 临时恢复一帧以将内容呈现在新 Surface 上，然后立即暂停
                NativeLibrary.unPauseEmulation()
                NativeLibrary.doFrame()
                NativeLibrary.pauseEmulation()
            } catch (_: Exception) { }
        }

        private fun tryApplyPerGameGraphicsApiOverride() {
            try {
                val ctx = CitraApplication.appContext
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                // Resolve titleId from path to check per-game switch
                val titleId = org.citra.citra_emu.NativeLibrary.getTitleId(gamePath)
                if (titleId == 0L) return
                val key = "override_graphics_api_" + titleId
                val enabled = prefs.getBoolean(key, false)
                if (!enabled) return

                // Read per-game selection: 0 system, 1 GL, 2 VK
                val selection = prefs.getInt("override_graphics_api_value_" + titleId, 0)
                if (selection == 0) return
                val targetApi = selection

                // Guard: if device lacks Vulkan support, skip overriding to Vulkan
                if (targetApi == 2) {
                    val pm = ctx.packageManager
                    val supportsVulkan = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
                                pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
                    } else false
                    if (!supportsVulkan) return
                    // Ensure Vulkan driver/hook is initialized before launching
                    try {
                        org.citra.citra_emu.utils.GpuDriverHelper.initializeDriverParameters()
                    } catch (_: Exception) { }
                }
                org.citra.citra_emu.NativeLibrary.setOverrideGraphicsApi(targetApi)
            } catch (_: Exception) { }
        }
    }

    private fun initializeBorderOverlay() {
        // Initialize the custom border overlay for 3DS screens (but don't start refresh yet)
        binding.customBorderOverlay?.let { borderView ->
            borderView.visibility = View.VISIBLE
        }
    }

    private fun startBorderRefresh(borderView: org.citra.citra_emu.overlay.BorderOverlayView) {
        // 直接启用边框，移除延时
        borderView.enableBorder()

        // 只在布局变化时刷新边框，而不是持续刷新
        setupLayoutChangeListener(borderView)
    }

    private fun setupLayoutChangeListener(borderView: org.citra.citra_emu.overlay.BorderOverlayView) {
        // 监听布局变化的关键时机
        var lastOrientation = resources.configuration.orientation
        var lastLandscapeLayout = IntSetting.SCREEN_LAYOUT.int
        var lastPortraitLayout = IntSetting.PORTRAIT_SCREEN_LAYOUT.int
        var lastSwapScreens = BooleanSetting.SWAP_SCREEN.boolean

        // 创建一个智能的检查函数
        val checkForChanges = {
            val currentOrientation = resources.configuration.orientation
            val currentLandscapeLayout = IntSetting.SCREEN_LAYOUT.int
            val currentPortraitLayout = IntSetting.PORTRAIT_SCREEN_LAYOUT.int
            val currentSwapScreens = BooleanSetting.SWAP_SCREEN.boolean

            val hasChanged = currentOrientation != lastOrientation ||
                    currentLandscapeLayout != lastLandscapeLayout ||
                    currentPortraitLayout != lastPortraitLayout ||
                    currentSwapScreens != lastSwapScreens

            if (hasChanged && emulationViewModel.emulationStarted.value) {
                lastOrientation = currentOrientation
                lastLandscapeLayout = currentLandscapeLayout
                lastPortraitLayout = currentPortraitLayout
                lastSwapScreens = currentSwapScreens
                
                // 延迟一点刷新，确保布局变化完成
                borderView.postDelayed({
                    borderView.refreshBorders()
                }, 100)
            }
        }

        // 使用更长的检查间隔（1秒），减少CPU使用
        val layoutCheckRunnable = object : Runnable {
            override fun run() {
                if (!requireActivity().isFinishing && !isDetached) {
                    checkForChanges()
                    borderView.postDelayed(this, 1000) // 1秒检查一次
                }
            }
        }
        borderView.post(layoutCheckRunnable)
    }


    companion object {
        private val perfStatsUpdateHandler = Handler(Looper.myLooper()!!)
    }
}
