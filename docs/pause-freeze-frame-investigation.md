# 暂停冻结帧 / 锁屏黑屏问题调研笔记

记录时间：2026-04-26 ～ 2026-04-28
撰写背景：尝试在 Vulkan 后端下，让游戏暂停 + 锁屏 / 切应用回来后，画面保持冻结帧（而非变黑）。多个 Android 侧 hack 方案最终回滚，问题根因在上游 Vulkan 渲染器。本文档供未来重启该任务时参考。

---

## 1. 问题陈述

### 现象
- Vulkan 后端，模拟器运行游戏中：
  - **场景 A（用户主动暂停 + 锁屏 / 切应用回来）**：屏幕变成纯黑色，游戏画面消失，包括用户曾 hardcode 的米白背景色（`src/common/settings.h:588-590`）
  - **场景 B（不暂停就锁屏 / 切应用回来）**：解锁后会"黑屏闪一下"才恢复游戏渲染
- OpenGL 后端：上述场景都正常（用户报告无此问题）

### 根因（已定位）
- `src/video_core/renderer_vulkan/renderer_vulkan.h:84` 的 `TryPresent(int timeout_ms, bool is_secondary)` 是**空实现**：
  ```cpp
  void TryPresent(int timeout_ms, bool is_secondary) override {}
  ```
- 对比 OpenGL：`src/video_core/renderer_opengl/renderer_opengl.cpp:899-938` 有完整实现，会从 mailbox 取出最近一帧重绘到当前 swapchain
- 当 SurfaceView 被 Android 销毁重建（锁屏/切应用），新 swapchain 需要画一帧才有内容。OpenGL 通过 `presentFrameWhilePaused()` → `NativeLibrary.doFrame()` → `TryPresent()` 完成；Vulkan 因为 TryPresent 是空函数，新 swapchain 永远显示初始（黑色）

### 调用链
```
EmulationFragment.onResume()
  → emulationState.presentFrameWhilePaused()    [EmulationFragment.kt:2567]
    → NativeLibrary.unPauseEmulation()
    → NativeLibrary.doFrame()                   [native.cpp:450]
      → window->TryPresenting()                 [emu_window.h:33 — 空 base]
        → RendererVulkan::TryPresent()          [renderer_vulkan.h:84 — 空]  ❌
    → NativeLibrary.pauseEmulation()
```

---

## 2. 已验证的事实（不要重新走一遍）

### 截图能力
- ✅ `PixelCopy.request(SurfaceView, ...)` 在 Vulkan SurfaceView 上**能正常工作**，返回 `result=PixelCopy.SUCCESS`
- ✅ Bitmap 内容真实（采样像素验证：center/corner/quarter 都是合理颜色，非全黑）
- ✅ 截图分辨率匹配视图：1080×2344（OPPO PKT110 全屏竖向）
- ✅ Bitmap 色彩空间默认是 `sRGB IEC61966-2.1`（与 SurfaceView 一致）

### 视图/合成行为（OPPO PKT110 / Android 16）
- ⚠️ **关键发现**：在该机器上，`elevation=0` 的常规 View **不会被绘制在 SurfaceView 内容之上**（与标准 Android 文档相悖）
  - 测试方法：ImageView 满屏、不透明蓝色背景、`elevation=0`，但屏幕仍能看到下方的 SurfaceView 游戏内容
  - 同样的 ImageView 设 `elevation=9dp` 后能完全覆盖屏幕
  - `pause_icon_overlay`（elevation 8dp）能正常覆盖游戏 → 提示阈值在 [0, 8] 之间
  - `custom_border_overlay`（elevation 0.5dp）正常工作 → 阈值可能很低（≤ 0.5dp）
- ⚠️ **另一关键发现**：把 `BorderOverlayView` 的 elevation 从 `0.5dp` 提到 `6.5dp` 后，游戏中黑色屏幕边框**反而消失了**——OPPO 的硬件层合成对 elevation 阈值非常敏感，不要随意改

### Citra 代码侧已知量
- `Log` 类（`org.citra.citra_emu.utils.Log`）是 JNI 调用，输出到 Citra 自己的日志文件，**不进 Android logcat**。诊断时必须用 `android.util.Log`
- `_binding` 在该项目中**从不在 onDestroyView 里 set null**（项目惯例，多个 if-null guard 散布在 fragment 各处）
- 用户 hardcode 在 `src/common/settings.h:588-590`：`bg_red/green/blue` 默认值改成 `0.878/0.875/0.859`（米白）
- BorderOverlayView 在 `commit 159a69c4f` 由用户加入，自定义画 3DS 屏幕边框

### 生命周期
- 用户主动暂停 → `togglePause()` → `emulationState.pause()` → `NativeLibrary.surfaceDestroyed()`（解绑 native 渲染器）
- 锁屏 → Android 调用 Activity 的 onPause → onStop（默认 fragment + binding 不销毁）
- 解锁 → onStart → onResume → 如果 `keepPausedRequested == true`，调 `presentFrameWhilePaused()`（Vulkan 下空操作）

---

## 3. 尝试过的方案 & 失败原因

> 全部已 revert（commit `93b4b0089`），仅作记录。

### 方案 A. PixelCopy SurfaceView → ImageView 覆盖

**思路**：用户按暂停时，PixelCopy 把 SurfaceView 截到 Bitmap，显示在覆盖层 ImageView 上。Bitmap 在 Java 堆，SurfaceView 销毁不影响。

**遇到的多个连环坑**：

#### 坑 1：异步 PixelCopy 与 pause() 的 race
- 第一版：`captureFrozenFrame()` 排队 PixelCopy 后，紧接着 `emulationState.pause()` → `NativeLibrary.surfaceDestroyed()` 立刻解绑 native，PixelCopy 回调跑的时候 buffer 已清空，截到黑帧
- **修法**：把 pause() 移到 PixelCopy 回调里执行（"capture-then-pause"），保证截到真实帧

#### 坑 2：ImageView 初始 `visibility=gone` → 切到 VISIBLE 时尺寸 0×0
- log 显示 `view w=0 h=0`，bitmap 设了但渲染不出来
- **修法**：XML 改 `visibility=invisible`（参与 layout），代码切 VISIBLE 时尺寸已就绪

#### 坑 3：OPPO 上 elevation=0 的 View 不在 SurfaceView 之上
- ImageView 即使 VISIBLE 且尺寸正确，也看不到（用蓝色填充测试，依然看见游戏）
- **修法**：给 ImageView 加 `elevation=6dp`（在 pause_overlay 7dp 之下）

#### 坑 4：截到的只有 SurfaceView 内容，缺 Android UI（按键、屏幕黑框）
- PixelCopy SurfaceView 只能拿 GPU 渲染部分；BorderOverlayView / InputOverlay / HotCornerOverlay 是 Android view 层，不在内
- **试 4a**：把这些 view 的 elevation 也提高到 6.5dp 让它们盖在冻结帧上 → **触发坑 5**
- **试 4b**：改成 PixelCopy 整个 Window，把所有 UI 都截进来 → 又出现新症状（见坑 6）

#### 坑 5：BorderOverlayView 抬到 6.5dp 后边框消失
- 在该 OPPO 上提升 elevation 似乎触发了硬件层渲染问题，BorderOverlayView 的画线反而不显示
- 该 view 在 elevation=0.5dp 时正常，6.5dp 时异常 → 行为非常脆弱

#### 坑 6：window 截图后，pause 期间正常但锁屏 → 解锁后**仍然黑屏**
- log 显示 `captured window 1080x2344 cs=sRGB` 多次成功
- 暂停未锁屏：冻结帧正确显示 ✅
- 锁屏 → 解锁：黑屏（甚至连冻结帧也消失）❌
- **未定位原因**，候选：
  - `loading_overlay`（elevation 10dp，纯黑，XML 默认 VISIBLE）在某种生命周期事件后被重新显示，盖住冻结帧
  - 某种 window/surface 重建逻辑令 ImageView drawable 失效
  - bitmap 引用被释放（不太可能，是 ARGB_8888 在 java 堆）

#### 坑 7：HDR/广色域光晕
- 不显式设色彩空间时，bitmap 在该 OPPO 上显示出"游戏内部物体边缘的发光"现象（云朵、花朵的 HDR-like halo）
- **修法**：`Bitmap.createBitmap(..., ColorSpace.get(ColorSpace.Named.SRGB))` 显式指定 sRGB，此问题缓解

### 方案 A 总评
- 修了 4-5 个独立坑后才看到部分效果
- 仍存在未定位的"锁屏后黑屏"残留 bug
- **根本局限**：只 hook 用户主动暂停场景，无法处理"不暂停直接锁屏"——而该场景才是 Vulkan 黑屏 bug 的真正全貌

### 方案 B（未实施）：Kotlin 短暂 unpause 让渲染循环跑 1-2 帧
- 思路：在 onResume 时短暂调 `unPauseEmulation()` 让正常渲染循环跑出一帧，再 `pauseEmulation()`
- **致命缺陷**：会让游戏状态前进若干帧，破坏"freeze 那一帧"的语义
- 从未实现

### 方案 C（未实施）：fragment_emulation.xml 加个静态米白背景色
- 思路：仅修复"看到黑色"的视觉症状，不真的还原画面
- **不满足用户需求**（需要保留游戏画面冻结），未实现

---

## 4. 推荐的最终方向：修 Vulkan TryPresent

### 思路
照抄 OpenGL 后端 `renderer_opengl.cpp:899-938` 的 `TryPresent` 实现，给 Vulkan 后端补一个等价版本。

### 设计要点
1. Vulkan renderer 维护"最近一次 present 的图像"引用（或拷贝）
2. `TryPresent()` 实现：
   - acquire 下一个 swapchain image
   - command buffer 录入：blit 上次图像 → swapchain image
   - 提交队列 + 信号量同步
   - present
3. 处理资源生命周期（swapchain 重建时释放旧引用）

### 需要的 Vulkan 知识
- VkCommandBuffer 录制 + submit
- VkSemaphore / VkFence 同步
- vkAcquireNextImageKHR / vkQueuePresentKHR
- vkCmdBlitImage 或 vkCmdCopyImage

### 复杂度估计
- ~100-200 行 C++
- 中等难度（Vulkan 啰嗦但有完整 OpenGL 模板可参考）
- 需要在真机测试（Mac 端无法跑 Android Vulkan）

### 优势
- ✅ 修真正的根因，不依赖 Android-side hack
- ✅ 同时覆盖**主动暂停 + 锁屏**与**不暂停直接锁屏**两个场景
- ✅ 行为与 OpenGL 后端对齐
- ✅ 可以推 PR 给 azahar 上游（这是上游真 bug）

### 劣势 / 风险
- ⚠️ 改动上游核心代码，rebase 上游时可能冲突
- ⚠️ Vulkan 同步错误不容易复现，可能在某些机型偶发
- ⚠️ 需要充分真机测试

### 实施清单（未来开始时参考）
1. 阅读 `renderer_opengl.cpp:899-938` 完整理解 OpenGL TryPresent
2. 阅读 `renderer_vulkan.cpp` 现有的 `SwapBuffers` / `RenderToWindow` 实现（line 248、1002）
3. 找 Vulkan renderer 的"最近一帧"存储位置（`clear_color` 在 line 248 设值，可能 frame data 也在附近）
4. 实现 `TryPresent` body
5. 真机测试三个场景：
   - 主动暂停 + 锁屏 + 解锁
   - 主动暂停 + 切应用 + 切回
   - 不暂停 + 锁屏 + 解锁
6. 让 Codex 做 review（codex:codex-rescue subagent）
7. 装 APK 给用户测；通过后 commit
8. 考虑推 PR 给 azahar 上游

---

## 5. 调试方法笔记（避免下次重新踩）

### 看 log
- **不要**用 Citra 的 `org.citra.citra_emu.utils.Log`（JNI，去日志文件不去 logcat）
- 用 `android.util.Log.d("YourTag", ...)`
- Android Studio Logcat 过滤框直接输入 tag（不要带方括号），或用 `tag:YourTag`
- 终端：`adb logcat -c && adb logcat -s "YourTag:*"`
- 在该项目中，build variant 默认是 `relWithDebInfo`，applicationId 后缀 `.debug`，包名 `io.github.lime3ds.android.debug`

### 验证 view 是否被渲染
- 给 view 设鲜艳纯色背景（蓝/红）—— 看不到该色 = 没渲染或被遮挡
- 多 elevation 试值（0 / 0.5dp / 6dp / 9dp）找出 SurfaceView 合成阈值

### 验证 bitmap 是否真有内容
- `bitmap.getPixel(x, y)` 采样几个点，log 出 hex 值
- 全 `0x00000000` 或 `0xFF000000` = 截到黑/空帧

### 测试场景
1. 暂停（不锁屏） — 验证主路径
2. 暂停 → 锁屏 → 解锁 — 验证 surface 重建
3. 暂停 → home 键 → 回应用 — 同上但稍不同
4. 不暂停 → 锁屏 → 解锁 — 上游 Vulkan bug 暴露场景
5. 反复点暂停/继续 — 检查 race / 内存泄漏

### 构建变体
- `:app:compileVanillaReleaseKotlin` — 仅 Kotlin 编译，几秒（验证语法）
- `:app:assembleVanillaRelease` — 完整 release APK
- `:app:assembleVanillaRelWithDebInfo` — debug-signed release，applicationId 后缀 `.debug`，**用户实际安装的就是这个变体**（IDE 默认）

---

## 6. 相关代码位置速查

| 用途 | 文件:行 |
|---|---|
| Vulkan TryPresent 空实现 | `src/video_core/renderer_vulkan/renderer_vulkan.h:84` |
| OpenGL TryPresent 参考实现 | `src/video_core/renderer_opengl/renderer_opengl.cpp:899-938` |
| Vulkan 渲染器 clear_color 设值 | `src/video_core/renderer_vulkan/renderer_vulkan.cpp:248-250, 1002-1005` |
| OpenGL glClearColor 设值 | `src/video_core/renderer_opengl/renderer_opengl.cpp:333-334` |
| 渲染器 base 的 bg_color_update_requested 标志 | `src/video_core/renderer_base.h:34` |
| native doFrame 入口 | `src/android/app/src/main/jni/native.cpp:450` |
| TryPresenting base（空） | `src/android/app/src/main/jni/emu_window/emu_window.h:33` |
| Kotlin presentFrameWhilePaused | `src/android/app/src/main/java/org/citra/citra_emu/fragments/EmulationFragment.kt:2567` |
| Kotlin togglePause | 同上 `:850` 起 |
| Kotlin onResume 暂停恢复路径 | 同上 `:890` 起 |
| Kotlin SurfaceHolder 回调 | 同上 `:2285-2297` |
| 用户 hardcode 的 bg_color 默认值 | `src/common/settings.h:588-590` |
| BorderOverlayView | `src/android/app/src/main/java/org/citra/citra_emu/overlay/BorderOverlayView.kt` |
| fragment_emulation.xml | `src/android/app/src/main/res/layout/fragment_emulation.xml` |

---

## 7. 关于 bg_color hardcode 的备注

用户在 `src/common/settings.h:588-590` 把 `bg_red/green/blue` 默认值从 `0.0f` 改成 `0.878f/0.875f/0.859f`（米白 #E0DFDB），因为最初 Layout 设置里没有背景色滑块。**现在该设置已经存在**（Settings → Layout → Background Color，三个 0-255 RGB 滑块）。

未来想还原 hardcode → 走标准设置流程：
1. 设备 Layout 设置里把 R/G/B 拖到 224/223/219 存盘
2. 改 `settings.h:588-590` 回 `0.0f`
3. 已经 hardcode 过的设备配置文件保留米白（除非重置设置），未保存过的设备会用新默认值（黑色）

注意：修复 Vulkan TryPresent 后，bg_color 不会再因锁屏丢失，所以可以放心还原 hardcode。

---

## 8. 状态总览

- 截至 2026-04-28：所有 Android-side hack 已 revert（commit `93b4b0089`），仓库回到没尝试过此问题的状态
- Vulkan TryPresent 修复方向：**未开始**，待用户决定是否启动
- bg_color hardcode：**保留**（`src/common/settings.h:588-590`），等 Vulkan 修复后再考虑还原
