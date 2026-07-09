// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <vector>

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include "jni/emu_window/emu_window.h"

namespace Core {
class System;
}

struct ANativeWindow;

// True once the primary OpenGL window surface has been created as FP16 + scRGB (extended range),
// i.e. capable of displaying the LCD shader's >1.0 highlights as HDR. Queried from JNI to decide
// whether to switch the Android Window into HDR colour mode.
bool IsGlHdrSurfacePresent();

class EmuWindow_Android_OpenGL : public EmuWindow_Android {
public:
    EmuWindow_Android_OpenGL(Core::System& system, ANativeWindow* surface, bool is_secondary,
                             EGLContext* sharedContext = NULL);
    ~EmuWindow_Android_OpenGL() override = default;

    void TryPresenting() override;
    void StopPresenting() override;
    void PollEvents() override;
    EGLContext* GetEGLContext() override;
    std::unique_ptr<GraphicsContext> CreateSharedContext() const override;

private:
    bool CreateWindowSurface() override;
    void DestroyWindowSurface() override;
    void DestroyContext() override;

private:
    Core::System& system;
    EGLConfig egl_config;
    EGLSurface egl_surface{};
    EGLContext egl_context{};
    EGLDisplay egl_display{};
    // Request an FP16/scRGB surface for HDR highlight output (primary window + LCD shader only).
    bool egl_hdr_requested{false};

    enum class PresentingState {
        Initial,
        Running,
        Stopped,
    };
    PresentingState presenting_state{};
};
