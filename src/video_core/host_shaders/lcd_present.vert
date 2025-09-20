// Copyright 2024 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.
// 
// LCD Shader - Vertex Shader
// Converted from melonds-android LCD shader for Azahar compatibility

//? #version 430 core
layout(location = 0) in vec2 vert_position;
layout(location = 1) in vec2 vert_tex_coord;
layout(location = 0) out vec2 frag_tex_coord;
layout(location = 1) out vec2 omega;

// This is a truncated 3x3 matrix for 2D transformations:
// The upper-left 2x2 submatrix performs scaling/rotation/mirroring.
// The third column performs translation.
// The third row could be used for projection, which we don't need in 2D. It hence is assumed to
// implicitly be [0, 0, 1]
uniform mat3x2 modelview_matrix;
uniform int is_portrait;

void main() {
    // Multiply input position by the rotscale part of the matrix and then manually translate by
    // the last column. This is equivalent to using a full 3x3 matrix and expanding the vector
    // to `vec3(vert_position.xy, 1.0)`
    gl_Position = vec4(mat2(modelview_matrix) * vert_position + modelview_matrix[2], 0.0, 1.0);
    frag_tex_coord = vert_tex_coord;
    
    // Calculate omega for LCD effect - using dynamic resolution from input
    // This will be used in the fragment shader for scanline and LCD grid calculations
    // Orientation-specific grid size: landscape = 1x, portrait = 1.5x
    // Note: Original grid size is 256, 384; 1.5x is 171, 256, 2x is 128, 192; 1.33x is 192, 288
    if (is_portrait == 1) {
        omega = 3.141592654 * 2.0 * vec2(171, 256); // 1.5x
    } else {
        omega = 3.141592654 * 2.0 * vec2(256, 384); // 1x
    }
}
