#version 330
#extension GL_ARB_separate_shader_objects : require

#include <lighting:lightdata.glsl>

// Applies the placed lights to the finished frame. Positions are rebuilt from the depth buffer with the world
// projection the markers wrote into the header row, so this needs no matrices from the post pipeline.
// No shadows: light passes through walls inside its radius. The vanilla light block at each light gives the real
// (white) block light; this pass tints and brightens what that light reaches.

uniform sampler2D InSampler;
uniform sampler2D MainDepthSampler;

layout(std140) uniform LightingConfig {
    float Gain;      // how much a light multiplies the surface colour it lands on
    float Ambient;   // additive part, so lights still show on surfaces vanilla left black
    float Wrap;      // 0 = hard N.L, 1 = surfaces facing away still get half
    float MaxBoost;  // cap on the summed light, keeps many overlapping lights from blowing out
    float Tint;      // how strongly a light also filters the surface towards its hue (blue light on white = blue)
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

mat4 lgt_projection() {
    mat4 m;
    for (int e = 0; e < 16; e++) {
        uint lo = lgt_uint(texelFetch(InSampler, ivec2(e * 2, 0), 0).rgb);
        uint hi = lgt_uint(texelFetch(InSampler, ivec2(e * 2 + 1, 0), 0).rgb) & 255u;
        m[e / 4][e % 4] = uintBitsToFloat((hi << 24) | lo);
    }
    return m;
}

vec3 view_at(mat4 invProj, ivec2 px, vec2 size) {
    float depth = texelFetch(MainDepthSampler, px, 0).r;
    #ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    float z = depth;
    #else
    float z = depth * 2.0 - 1.0;
    #endif
    vec4 v = invProj * vec4((vec2(px) + 0.5) / size * 2.0 - 1.0, z, 1.0);
    return v.xyz / v.w;
}

vec3 pick(vec3 a, vec3 b) {
    return abs(a.z) < abs(b.z) ? a : b;
}

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec4 scene = texelFetch(InSampler, px, 0);
    if (lgt_uint(texelFetch(InSampler, ivec2(32, 0), 0).rgb) != LGT_MAGIC_HEADER) {
        fragColor = scene;
        return;
    }
    // the data block itself: show the pixels just above it instead
    if (px.x < LGT_REGION_W && px.y < LGT_REGION_H) {
        px.y += LGT_REGION_H;
        scene = texelFetch(InSampler, px, 0);
    }
    if (texelFetch(MainDepthSampler, px, 0).r >= 1.0) {
        fragColor = scene; // sky
        return;
    }

    vec2 size = vec2(textureSize(InSampler, 0));
    mat4 invProj = inverse(lgt_projection());
    vec3 view = view_at(invProj, px, size);
    // normal from the neighbours on the flatter side, so silhouettes don't smear
    vec3 dx = pick(view_at(invProj, px + ivec2(1, 0), size) - view, view - view_at(invProj, px - ivec2(1, 0), size));
    vec3 dy = pick(view_at(invProj, px + ivec2(0, 1), size) - view, view - view_at(invProj, px - ivec2(0, 1), size));
    vec3 n = normalize(cross(dx, dy));
    if (dot(n, view) > 0.0) n = -n;

    vec3 sum = vec3(0.0);
    vec3 filt = vec3(0.0);
    for (int s = 0; s < LGT_MAX; s++) {
        uvec3 p = uvec3(round(texelFetch(InSampler, lgt_light_px(s, 4), 0).rgb * 255.0));
        if (p.b != LGT_MAGIC_LIGHT || p.r == 0u) continue;
        float radius = float(p.r) / 8.0;
        vec3 lp = vec3(lgt_dec_fixed(texelFetch(InSampler, lgt_light_px(s, 0), 0).rgb),
                       lgt_dec_fixed(texelFetch(InSampler, lgt_light_px(s, 1), 0).rgb),
                       lgt_dec_fixed(texelFetch(InSampler, lgt_light_px(s, 2), 0).rgb));
        vec3 l = lp - view;
        float d2 = dot(l, l);
        if (d2 >= radius * radius) continue;
        float d = sqrt(d2);
        float x = d / radius;
        // windowed inverse-square: bright core, reaches exactly 0 at the radius
        float w = 1.0 - x * x;
        float att = w * w / (1.0 + 6.0 * x * x);
        float ndl = clamp((dot(n, l / max(d, 0.001)) + Wrap) / (1.0 + Wrap), 0.0, 1.0);
        vec3 colour = texelFetch(InSampler, lgt_light_px(s, 3), 0).rgb;
        float k = (float(p.g) / 64.0) * att * ndl;
        sum += colour * k;
        filt += (colour / max(max(colour.r, colour.g), max(colour.b, 0.001)) - 1.0) * min(k, 1.0);
    }
    sum = min(sum, vec3(MaxBoost));
    vec3 hueFilter = clamp(1.0 + filt * Tint, 0.0, 1.0);
    fragColor = vec4(scene.rgb * hueFilter * (1.0 + sum * Gain) + sum * Ambient, scene.a);
}
