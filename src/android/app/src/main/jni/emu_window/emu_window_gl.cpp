// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdlib>
#include <string>
#include <string_view>

#include <android/native_window_jni.h>
#include <glad/glad.h>

#include "common/logging/log.h"
#include "common/settings.h"
#include "core/core.h"
#include "input_common/main.h"
#include "jni/emu_window/emu_window_gl.h"
#include "video_core/gpu.h"
#include "video_core/renderer_base.h"

static constexpr std::array<EGLint, 15> egl_attribs{EGL_SURFACE_TYPE,
                                                    EGL_WINDOW_BIT,
                                                    EGL_RENDERABLE_TYPE,
                                                    EGL_OPENGL_ES3_BIT_KHR,
                                                    EGL_BLUE_SIZE,
                                                    8,
                                                    EGL_GREEN_SIZE,
                                                    8,
                                                    EGL_RED_SIZE,
                                                    8,
                                                    EGL_DEPTH_SIZE,
                                                    0,
                                                    EGL_STENCIL_SIZE,
                                                    0,
                                                    EGL_NONE};
static constexpr std::array<EGLint, 5> egl_empty_attribs{EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
static constexpr std::array<EGLint, 4> egl_context_attribs{EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};

// --- HDR (FP16 + scRGB extended range) ---------------------------------------
// Some NDK EGL headers predate these extensions; define the tokens defensively.
#ifndef EGL_GL_COLORSPACE_KHR
#define EGL_GL_COLORSPACE_KHR 0x309D
#endif
#ifndef EGL_GL_COLORSPACE_SCRGB_EXT
#define EGL_GL_COLORSPACE_SCRGB_EXT 0x3351
#endif
#ifndef EGL_COLOR_COMPONENT_TYPE_EXT
#define EGL_COLOR_COMPONENT_TYPE_EXT 0x3339
#endif
#ifndef EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT
#define EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT 0x333B
#endif

// Half-float RGBA config so the compositor can receive scRGB values > 1.0 (HDR highlights).
static constexpr std::array<EGLint, 19> egl_attribs_fp16{EGL_SURFACE_TYPE,
                                                         EGL_WINDOW_BIT,
                                                         EGL_RENDERABLE_TYPE,
                                                         EGL_OPENGL_ES3_BIT_KHR,
                                                         EGL_COLOR_COMPONENT_TYPE_EXT,
                                                         EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT,
                                                         EGL_RED_SIZE,
                                                         16,
                                                         EGL_GREEN_SIZE,
                                                         16,
                                                         EGL_BLUE_SIZE,
                                                         16,
                                                         EGL_ALPHA_SIZE,
                                                         16,
                                                         EGL_DEPTH_SIZE,
                                                         0,
                                                         EGL_STENCIL_SIZE,
                                                         0,
                                                         EGL_NONE};

// Set true once the primary window's scRGB surface is created; read from the JNI thread.
static std::atomic<bool> g_gl_hdr_surface_present{false};

static bool HasHdrEglExtensions(EGLDisplay display) {
    const char* ext = eglQueryString(display, EGL_EXTENSIONS);
    if (!ext) {
        return false;
    }
    const std::string_view s{ext};
    return s.find("EGL_EXT_pixel_format_float") != std::string_view::npos &&
           s.find("EGL_EXT_gl_colorspace_scrgb") != std::string_view::npos;
}

// The LCD present shader is the only content that emits values > 1.0, so only pay the FP16 cost
// (and only risk the extended-range path) when it is the selected post-processing shader.
static bool IsLcdPresentShaderActive() {
    const std::string& name = Settings::values.pp_shader_name.GetValue();
    return name == "lcd (builtin)" || name == "lcd 4/3 (builtin)";
}

bool IsGlHdrSurfacePresent() {
    return g_gl_hdr_surface_present.load();
}

class SharedContext_Android : public Frontend::GraphicsContext {
public:
    SharedContext_Android(EGLDisplay egl_display, EGLConfig egl_config,
                          EGLContext egl_share_context)
        : egl_display{egl_display},
          egl_surface{eglCreatePbufferSurface(egl_display, egl_config, egl_empty_attribs.data())},
          egl_context{eglCreateContext(egl_display, egl_config, egl_share_context,
                                       egl_context_attribs.data())} {
        ASSERT_MSG(egl_surface, "eglCreatePbufferSurface() failed!");
        ASSERT_MSG(egl_context, "eglCreateContext() failed!");
    }

    ~SharedContext_Android() override {
        if (!eglDestroySurface(egl_display, egl_surface)) {
            LOG_CRITICAL(Frontend, "eglDestroySurface() failed");
        }

        if (!eglDestroyContext(egl_display, egl_context)) {
            LOG_CRITICAL(Frontend, "eglDestroySurface() failed");
        }
    }

    void MakeCurrent() override {
        eglMakeCurrent(egl_display, egl_surface, egl_surface, egl_context);
    }

    void DoneCurrent() override {
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }

private:
    EGLDisplay egl_display{};
    EGLSurface egl_surface{};
    EGLContext egl_context{};
};

EmuWindow_Android_OpenGL::EmuWindow_Android_OpenGL(Core::System& system_, ANativeWindow* surface,
                                                   bool is_secondary, EGLContext* sharedContext)
    : EmuWindow_Android{surface, is_secondary}, system{system_} {
    if (egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY); egl_display == EGL_NO_DISPLAY) {
        LOG_CRITICAL(Frontend, "eglGetDisplay() failed");
        return;
    }
    if (eglInitialize(egl_display, 0, 0) != EGL_TRUE) {
        LOG_CRITICAL(Frontend, "eglInitialize() failed");
        return;
    }
    // Prefer an FP16 config for HDR highlight output when the LCD shader is active and the driver
    // advertises the required extensions; otherwise fall back to the standard 8-bit config.
    // Both windows use the same (FP16) config so the shared GL context stays compatible with the
    // secondary window's surface (an FP16 context + 8-bit surface would fail eglMakeCurrent); only
    // the primary window actually drives HDR mode (see g_gl_hdr_surface_present below).
    egl_hdr_requested = IsLcdPresentShaderActive() && HasHdrEglExtensions(egl_display);
    bool config_ok = false;
    if (egl_hdr_requested) {
        EGLint n{};
        if (eglChooseConfig(egl_display, egl_attribs_fp16.data(), &egl_config, 1, &n) == EGL_TRUE &&
            n > 0) {
            config_ok = true;
            LOG_INFO(Frontend, "HDR: selected FP16 EGLConfig for scRGB output");
        } else {
            egl_hdr_requested = false;
            LOG_INFO(Frontend, "HDR: FP16 config unavailable, using 8-bit");
        }
    }
    if (!config_ok) {
        if (EGLint egl_num_configs{}; eglChooseConfig(egl_display, egl_attribs.data(), &egl_config,
                                                      1, &egl_num_configs) != EGL_TRUE) {
            LOG_CRITICAL(Frontend, "eglChooseConfig() failed");
            return;
        }
    }

    CreateWindowSurface();

    if (eglQuerySurface(egl_display, egl_surface, EGL_WIDTH, &window_width) != EGL_TRUE) {
        return;
    }
    if (eglQuerySurface(egl_display, egl_surface, EGL_HEIGHT, &window_height) != EGL_TRUE) {
        return;
    }
    if (sharedContext) {
        egl_context = *sharedContext;
    } else if (egl_context =
                   eglCreateContext(egl_display, egl_config, 0, egl_context_attribs.data());
               egl_context == EGL_NO_CONTEXT) {
        LOG_CRITICAL(Frontend, "eglCreateContext() failed");
        return;
    }
    if (eglSurfaceAttrib(egl_display, egl_surface, EGL_SWAP_BEHAVIOR, EGL_BUFFER_DESTROYED) !=
        EGL_TRUE) {
        LOG_CRITICAL(Frontend, "eglSurfaceAttrib() failed");
        return;
    }
    if (core_context = CreateSharedContext(); !core_context) {
        LOG_CRITICAL(Frontend, "CreateSharedContext() failed");
        return;
    }
    if (eglMakeCurrent(egl_display, egl_surface, egl_surface, egl_context) != EGL_TRUE) {
        LOG_CRITICAL(Frontend, "eglMakeCurrent() failed");
        return;
    }
    if (!gladLoadGLES2Loader((GLADloadproc)eglGetProcAddress)) {
        LOG_CRITICAL(Frontend, "gladLoadGLES2Loader() failed");
        return;
    }
    if (!eglSwapInterval(egl_display, Settings::values.use_vsync ? 1 : 0)) {
        LOG_CRITICAL(Frontend, "eglSwapInterval() failed");
        return;
    }

    OnFramebufferSizeChanged();
}

EGLContext* EmuWindow_Android_OpenGL::GetEGLContext() {
    return &egl_context;
}

bool EmuWindow_Android_OpenGL::CreateWindowSurface() {
    if (!host_window) {
        return true;
    }

    EGLint format{};
    eglGetConfigAttrib(egl_display, egl_config, EGL_NATIVE_VISUAL_ID, &format);
    ANativeWindow_setBuffersGeometry(host_window, 0, 0, format);

    egl_surface = EGL_NO_SURFACE;
    bool scrgb_ok = false;
    if (egl_hdr_requested) {
        const EGLint scrgb_attribs[] = {EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_SCRGB_EXT,
                                        EGL_NONE};
        egl_surface = eglCreateWindowSurface(egl_display, egl_config, host_window, scrgb_attribs);
        scrgb_ok = egl_surface != EGL_NO_SURFACE;
        if (!scrgb_ok) {
            LOG_WARNING(Frontend, "HDR: scRGB window surface failed, using default colourspace");
        }
    }
    if (egl_surface == EGL_NO_SURFACE) {
        egl_surface = eglCreateWindowSurface(egl_display, egl_config, host_window, 0);
    }

    // Only the primary window's scRGB surface drives HDR; expose the outcome to the JNI query.
    if (!is_secondary) {
        g_gl_hdr_surface_present = scrgb_ok && egl_surface != EGL_NO_SURFACE;
        if (egl_hdr_requested) {
            LOG_INFO(Frontend, "HDR: primary scRGB surface present={}",
                     g_gl_hdr_surface_present.load());
        }
    }

    if (egl_surface == EGL_NO_SURFACE) {
        return {};
    }

    return egl_surface;
}

void EmuWindow_Android_OpenGL::DestroyWindowSurface() {
    if (!egl_surface) {
        return;
    }
    if (eglGetCurrentSurface(EGL_DRAW) == egl_surface) {
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (!eglDestroySurface(egl_display, egl_surface)) {
        LOG_CRITICAL(Frontend, "eglDestroySurface() failed");
    }
    egl_surface = EGL_NO_SURFACE;
}

void EmuWindow_Android_OpenGL::DestroyContext() {
    if (!egl_context) {
        return;
    }
    if (eglGetCurrentContext() == egl_context) {
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (!eglDestroyContext(egl_display, egl_context)) {
        LOG_CRITICAL(Frontend, "eglDestroySurface() failed");
    }
    if (!eglTerminate(egl_display)) {
        LOG_CRITICAL(Frontend, "eglTerminate() failed");
    }
    egl_context = EGL_NO_CONTEXT;
    egl_display = EGL_NO_DISPLAY;
}

std::unique_ptr<Frontend::GraphicsContext> EmuWindow_Android_OpenGL::CreateSharedContext() const {
    return std::make_unique<SharedContext_Android>(egl_display, egl_config, egl_context);
}

void EmuWindow_Android_OpenGL::PollEvents() {
    if (!render_window) {
        return;
    }

    host_window = render_window;
    render_window = nullptr;

    DestroyWindowSurface();
    CreateWindowSurface();
    OnFramebufferSizeChanged();
    presenting_state = PresentingState::Initial;
}

void EmuWindow_Android_OpenGL::StopPresenting() {
    if (presenting_state == PresentingState::Running) {
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    presenting_state = PresentingState::Stopped;
}

void EmuWindow_Android_OpenGL::TryPresenting() {
    if (!system.IsPoweredOn()) {
        return;
    }
    if (presenting_state == PresentingState::Initial) [[unlikely]] {
        presenting_state = PresentingState::Running;
    }
    if (presenting_state != PresentingState::Running) [[unlikely]] {
        return;
    }
    eglMakeCurrent(egl_display, egl_surface, egl_surface, egl_context);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    eglSwapInterval(egl_display, Settings::values.use_vsync ? 1 : 0);
    system.GPU().Renderer().TryPresent(0, is_secondary);
    eglSwapBuffers(egl_display, egl_surface);
}
