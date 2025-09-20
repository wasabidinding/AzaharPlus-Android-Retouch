// Copyright 2024 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.
//
// LCD Shader - Fragment Shader
// Converted from melonds-android LCD shader for Azahar compatibility
// 
// Original Author: Gigaherz
// License: Public domain
// 
// This shader simulates LCD display effects including:
// - Scanline effects (horizontal lines)
// - LCD pixel grid effects
// - Brightness adjustments for authentic retro feel

//? #version 430 core

layout(location = 0) in vec2 frag_tex_coord;
layout(location = 1) in vec2 omega;
layout(location = 0) out vec4 color;

layout(binding = 0) uniform sampler2D color_texture;

uniform vec4 i_resolution;
uniform vec4 o_resolution;
uniform int layer;

/* configuration (higher values mean brighter image but reduced effect depth) */
const float brighten_scanlines = 16.0;
const float brighten_lcd = 4.0;

const vec3 offsets = 3.141592654 * vec3(1.0/2.0,1.0/2.0 - 2.0/3.0,1.0/2.0-4.0/3.0);

void main() {
    vec2 angle = frag_tex_coord * omega;
    
    // Calculate scanline effect (horizontal lines)
    float yfactor = (brighten_scanlines + sin(angle.y)) / (brighten_scanlines + 1.0);
    
    // Calculate LCD grid effect (vertical pixel structure)
    vec3 xfactors = (brighten_lcd + sin(angle.x + offsets)) / (brighten_lcd + 1.0);
    
    // Sample the original texture and apply LCD effects
    vec4 original_color = texture(color_texture, frag_tex_coord);
    
    // Apply the LCD effects to the color
    // Note: Original shader used .bgr, but we'll use .rgb for standard color order
    color.rgb = yfactor * xfactors * original_color.rgb;
    color.a = original_color.a;
}


