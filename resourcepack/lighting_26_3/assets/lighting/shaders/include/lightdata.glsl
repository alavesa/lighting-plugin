#ifndef LIGHTING_LIGHTDATA_GLSL
#define LIGHTING_LIGHTDATA_GLSL

// Light data travels from the marker items (core/item) to the post pass (post/colored_lights) through a small
// block of pixels in the bottom-left corner of the main frame. Every value is 24 bits packed into RGB8.
//
//   row 0, x 0..31   the world projection matrix, 16 floats, 2 px each (low 24 bits, then the high 8 bits)
//   row 0, x 32      header magic: only frames that drew at least one marker carry it
//   row 1 + s/4      light slot s, x = (s % 4) * 5 + k:
//                      k 0..2  view-space x, y, z (24-bit fixed point over +-LGT_POS_RANGE)
//                      k 3     colour (the item's first tint)
//                      k 4     radius * 8, intensity * 64, LGT_MAGIC_LIGHT (the item's second tint)
//
// The marker texture (lighting:item/marker, 16x16) has one key texel per (kind, slot): R 217, G 41,
// B = kind * 64 + slot. kind 0 draws k 0..3, kind 1 draws k 4, kind 2 draws the header row.

const int LGT_MAX = 64;
const int LGT_PER_ROW = 4;
const int LGT_PX = 5;
const int LGT_REGION_W = 33;
const int LGT_REGION_H = 1 + LGT_MAX / LGT_PER_ROW;
const float LGT_POS_RANGE = 512.0;
const uint LGT_MAGIC_HEADER = 0xD929AAu; // 217, 41, 170
const uint LGT_MAGIC_LIGHT = 165u;

ivec2 lgt_light_px(int slot, int k) {
    return ivec2((slot % LGT_PER_ROW) * LGT_PX + k, 1 + slot / LGT_PER_ROW);
}

vec3 lgt_bytes(uint v) {
    return vec3(float((v >> 16) & 255u), float((v >> 8) & 255u), float(v & 255u)) / 255.0;
}

uint lgt_uint(vec3 c) {
    uvec3 b = uvec3(round(clamp(c, 0.0, 1.0) * 255.0));
    return (b.r << 16) | (b.g << 8) | b.b;
}

vec3 lgt_enc_fixed(float v) {
    float t = clamp((v + LGT_POS_RANGE) / (2.0 * LGT_POS_RANGE), 0.0, 1.0);
    return lgt_bytes(uint(t * 16777215.0 + 0.5));
}

float lgt_dec_fixed(vec3 c) {
    return float(lgt_uint(c)) / 16777215.0 * (2.0 * LGT_POS_RANGE) - LGT_POS_RANGE;
}

#endif
