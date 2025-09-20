// Copyright 2024 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <jni.h>
#include <android/log.h>
#include <core/core.h>
#include "android_common/android_common.h"

// Forward declaration of the function we need to call
namespace OpenGL {
    void UpdatePerGameLcdSetting(int setting);
}

extern "C" {

JNIEXPORT jint JNICALL
Java_org_citra_citra_1emu_NativeLibrary_getPerGameLcdSetting(JNIEnv* env, jclass clazz, jlong titleId) {
    // This function is not used anymore since we store the setting directly in the renderer
    // Return 0 (system default) as fallback
    return 0;
}

JNIEXPORT void JNICALL
Java_org_citra_citra_1emu_NativeLibrary_updatePerGameLcdSetting(JNIEnv* env, jclass clazz, jint setting) {
    // Update the per-game LCD setting in the renderer
    // 0 = system default, 1 = LCD on, 2 = LCD off
    OpenGL::UpdatePerGameLcdSetting(setting);
}

} // extern "C"
