#ifndef LIGHTING_MARKER_VERTEX_GLSL
#define LIGHTING_MARKER_VERTEX_GLSL

#include <lighting:lightdata.glsl>

// Needs (from the including shader): Sampler0, Position, UV0, Color, Normal, ModelViewMat, ScreenSize.
layout(location = 8) flat out int lgtKind;   // 0 = an ordinary item, 1..3 = marker kind + 1
layout(location = 9) flat out int lgtSlot;
layout(location = 10) flat out vec3 lgtView;
layout(location = 11) flat out vec3 lgtTint;

// True when this vertex belongs to a light marker; then gl_Position is replaced by a quad over the marker's pixels.
bool lgt_marker(inout vec4 position) {
    lgtKind = 0;
    lgtSlot = 0;
    lgtView = vec3(0.0);
    lgtTint = vec3(0.0);
    vec4 key = textureLod(Sampler0, UV0, 0.0);
    uvec3 b = uvec3(round(key.rgb * 255.0));
    if (b.r != 217u || b.g != 41u || key.a < 0.99 || b.b >= 192u) return false;
    int kind = int(b.b) / 64;
    int slot = int(b.b) % 64;
    lgtKind = kind + 1;
    lgtSlot = slot;
    lgtView = (ModelViewMat * vec4(Position, 1.0)).xyz;
    lgtTint = Color.rgb;
    if (kind == 1 && length(lgtView) > LGT_POS_RANGE - 1.0) lgtTint.b = 0.0; // too far to encode: invalid

    vec2 x0y0;
    vec2 x1y1;
    if (kind == 0) {
        x0y0 = vec2(lgt_light_px(slot, 0));
        x1y1 = x0y0 + vec2(4.0, 1.0);
    } else if (kind == 1) {
        x0y0 = vec2(lgt_light_px(slot, 4));
        x1y1 = x0y0 + vec2(1.0, 1.0);
    } else {
        x0y0 = vec2(0.0);
        x1y1 = vec2(float(LGT_REGION_W), 1.0);
    }
    // the model has a front and a back face per kind; they get opposite windings, so exactly one survives culling
    int corner = gl_VertexIndex % 4;
    if (dot(Normal, vec3(0.31, 0.57, 0.76)) < 0.0) corner = 3 - corner;
    vec2 c = corner == 0 ? vec2(0.0, 0.0) : corner == 1 ? vec2(0.0, 1.0) : corner == 2 ? vec2(1.0, 1.0) : vec2(1.0, 0.0);
    vec2 px = mix(x0y0, x1y1, c);
    position = vec4(px / ScreenSize * 2.0 - 1.0, 0.0, 1.0);
    return true;
}

#endif
