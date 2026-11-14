#ifndef LIGHTING_MARKER_FRAGMENT_GLSL
#define LIGHTING_MARKER_FRAGMENT_GLSL

#include <lighting:lightdata.glsl>

// Needs (from the including shader): ProjMat.
layout(location = 8) flat in int lgtKind;
layout(location = 9) flat in int lgtSlot;
layout(location = 10) flat in vec3 lgtView;
layout(location = 11) flat in vec3 lgtTint;

vec4 lgt_marker_color() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    if (lgtKind == 1) {
        int k = px.x - lgt_light_px(lgtSlot, 0).x;
        if (k == 3) return vec4(lgtTint, 1.0);
        return vec4(lgt_enc_fixed(k == 0 ? lgtView.x : k == 1 ? lgtView.y : lgtView.z), 1.0);
    }
    if (lgtKind == 2) return vec4(lgtTint, 1.0);
    if (px.x >= 32) return vec4(lgt_bytes(LGT_MAGIC_HEADER), 1.0);
    int e = px.x / 2;
    uint bits = floatBitsToUint(ProjMat[e / 4][e % 4]);
    return vec4(lgt_bytes((px.x % 2) == 0 ? (bits & 0xFFFFFFu) : (bits >> 24)), 1.0);
}

#endif
