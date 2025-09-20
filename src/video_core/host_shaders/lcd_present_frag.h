// Copyright 2024 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <string_view>

namespace HostShaders {

constexpr std::string_view LCD_PRESENT_FRAG = {
"// Copyright 2024 Azahar Emulator Project\n"
"// Licensed under GPLv2 or any later version\n"
"// Refer to the license.txt file included.\n"
"//\n"
"// LCD Shader - Fragment Shader\n"
"// Converted from melonds-android LCD shader for Azahar compatibility\n"
"// \n"
"// Original Author: Gigaherz\n"
"// License: Public domain\n"
"// \n"
"// This shader simulates LCD display effects including:\n"
"// - Scanline effects (horizontal lines)\n"
"// - LCD pixel grid effects\n"
"// - Brightness adjustments for authentic retro feel\n"
"\n"
"//? #version 430 core\n"
"\n"
"layout(location = 0) in vec2 frag_tex_coord;\n"
"layout(location = 1) in vec2 omega;\n"
"layout(location = 0) out vec4 color;\n"
"\n"
"layout(binding = 0) uniform sampler2D color_texture;\n"
"\n"
"uniform vec4 i_resolution;\n"
"uniform vec4 o_resolution;\n"
"uniform int layer;\n"
"\n"
"/* configuration (higher values mean brighter image but reduced effect depth) */\n"
"const float brighten_scanlines = 16.0;\n"
"const float brighten_lcd = 4.0;\n"
"\n"
"const vec3 offsets = 3.141592654 * vec3(1.0/2.0,1.0/2.0 - 2.0/3.0,1.0/2.0-4.0/3.0);\n"
"\n"
"void main() {\n"
"    vec2 angle = frag_tex_coord * omega;\n"
"    \n"
"    // Calculate scanline effect (horizontal lines)\n"
"    float yfactor = (brighten_scanlines + sin(angle.y)) / (brighten_scanlines + 1.0);\n"
"    \n"
"    // Calculate LCD grid effect (vertical pixel structure)\n"
"    vec3 xfactors = (brighten_lcd + sin(angle.x + offsets)) / (brighten_lcd + 1.0);\n"
"    \n"
"    // Sample the original texture and apply LCD effects\n"
"    vec4 original_color = texture(color_texture, frag_tex_coord);\n"
"    \n"
"    // Apply the LCD effects to the color\n"
"    // Note: Original shader used .bgr, but we'll use .rgb for standard color order\n"
"    color.rgb = yfactor * xfactors * original_color.rgb;\n"
"    color.a = original_color.a;\n"
"}\n"
"\n"
};

} // namespace HostShaders


