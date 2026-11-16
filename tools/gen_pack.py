"""Generates every Lighting pack asset (no shaders): soft alpha-gradient textures in 8 opacity steps, flat decal
models, the multi-plane beam cone, tintable item definitions (minecraft:dye tint = DYED_COLOR), the wand and the
flashlight. Run from the repo root: python3 tools/gen_pack.py"""
import json, math, os, shutil, struct, zlib

ROOT = 'resourcepack/assets/lighting'
LEVELS = 8          # opacity steps, picked with custom_model_data floats[0] = 0..7
SIZE = 128          # decal texture size (texel = radius / 64 blocks)


def png(path, w, h, px):
    raw = b''.join(b'\0' + bytes(c for x in range(w) for c in px[y * w + x]) for y in range(h))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'wb').write(data)


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(obj, f, indent=2)
        f.write('\n')


def smooth(e0, e1, x):
    t = min(1.0, max(0.0, (x - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)


# alpha profiles over r = 0 (centre) .. 1 (edge); all reach exactly 0 at the edge
PROFILES = {
    # broad soft pool of light: a light's footprint on floors, walls and ceilings
    'glow': lambda r: (1 - smooth(0.0, 1.0, r)) ** 2.2,
    # flashlight hotspot: bright core, a faint lens ring at 0.62 (the "gobo"), soft edge
    'hotspot': lambda r: min(1.0, math.exp(-(r / 0.42) ** 2) + 0.28 * math.exp(-((r - 0.62) / 0.08) ** 2))
                         * (1 - smooth(0.82, 1.0, r)),
    # flashlight spill: the wide dim ring around the hotspot
    'spill': lambda r: smooth(0.15, 0.55, r) * (1 - smooth(0.6, 1.0, r)) * 0.9 + 0.1 * (1 - smooth(0.0, 1.0, r)),
    # halo around the source itself (billboard): tight gaussian
    'halo': lambda r: math.exp(-(r / 0.38) ** 2) * (1 - smooth(0.85, 1.0, r)),
}

# 4x4 ordered dither breaks up 8-bit alpha banding when a texel spans a lot of screen
BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5]
# 8x8 ordered dither for the fringe: translucent items discard pixels under alpha 0.1, which would cut a soft
# tail off in one hard step. Below the cutoff, texels are either exactly at it or empty, in proportion to the wanted
# alpha, so the tail still fades smoothly on average.
B8 = [0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26, 12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20,
      62, 30, 54, 22, 3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25, 15, 47, 7, 39, 13, 45, 5, 37,
      63, 31, 55, 23, 61, 29, 53, 21]
CUT = 27  # first alpha (of 255) that survives the client's 0.1 cutout


def fringe(a, x, y):
    if a >= CUT:
        return a
    # only the outer half of the fade is dithered; the faintest part is simply dropped (a narrow, fine grain band)
    t = (a - CUT * 0.5) / (CUT * 0.5)
    return CUT if t > 0 and (B8[(y % 8) * 8 + x % 8] + 0.5) / 64 < t else 0

for name, prof in PROFILES.items():
    # the glow covers the biggest areas (whole walls): twice the resolution keeps its edge grain fine
    size = SIZE * 2 if name == 'glow' else SIZE
    for lv in range(LEVELS):
        k = (lv + 1) / LEVELS
        px = []
        for y in range(size):
            for x in range(size):
                r = math.hypot(x + 0.5 - size / 2, y + 0.5 - size / 2) / (size / 2)
                a = prof(min(r, 1.0)) * k * 255 + (BAYER[(y % 4) * 4 + x % 4] / 16.0 - 0.5)
                a = max(0, min(255, int(round(a))))
                # the broad glow/halo fade out through a dithered fringe; flashlight spots keep a crisp edge
                # (they get stretched along the surface, which would blow the dither up into a checkerboard)
                if name in ('glow', 'halo'):
                    a = fringe(a, x, y)
                px.append((255, 255, 255, a))
        png(f'{ROOT}/textures/item/light/{name}_{lv}.png', size, size, px)

# beam: u across the cone (narrow at v=0, the lamp), v along it; fades with length and towards the edges
BW, BH = 64, 128
for lv in range(LEVELS):
    k = (lv + 1) / LEVELS
    px = []
    for y in range(BH):
        t = (y + 0.5) / BH                       # 0 at the lamp .. 1 at the far end
        half = 0.08 + 0.92 * t                   # cone half-width in u
        for x in range(BW):
            u = abs((x + 0.5) / BW - 0.5) * 2    # 0 centre .. 1 edge
            edge = 1 - smooth(half * 0.55, half, u)
            a = 0.55 * edge * (1 - smooth(0.25, 1.0, t)) * smooth(0.0, 0.08, t) * k * 255
            a += BAYER[(y % 4) * 4 + x % 4] / 16.0 - 0.5
            px.append((255, 255, 255, max(0, min(255, int(round(a))))))
    png(f'{ROOT}/textures/item/light/beam_{lv}.png', BW, BH, px)

tint = [{'type': 'minecraft:dye', 'default': -1}]

# flat decal: a zero-thickness square in the model's XY plane through the centre, both faces drawn.
# With ItemDisplayTransform NONE the model is centred on the entity, so local +Z is the surface normal.
for name in PROFILES:
    for lv in range(LEVELS):
        f = {'uv': [0, 0, 16, 16], 'texture': '#t', 'tintindex': 0}
        write(f'{ROOT}/models/item/light/{name}_{lv}.json', {
            'textures': {'t': f'lighting:item/light/{name}_{lv}', 'particle': f'lighting:item/light/{name}_{lv}'},
            'elements': [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False, 'light_emission': 15,
                          'faces': {'north': f, 'south': f}}]})

# beam cone: four planes through the Z axis (0, 45, 90, 135 degrees) so it reads as a volume from any side.
# Model z 0..16 = lamp .. far end; the plugin scales it to the beam length and cone width.
for lv in range(LEVELS):
    fh = {'uv': [0, 0, 16, 16], 'texture': '#t', 'tintindex': 0}
    fv = {'uv': [0, 0, 16, 16], 'texture': '#t', 'tintindex': 0, 'rotation': 90}
    planes = []
    for ang in (0, 45, -45):
        e = {'from': [0, 8, 0], 'to': [16, 8, 16], 'shade': False, 'light_emission': 15,
             'faces': {'up': fh, 'down': fh}}
        if ang:
            e['rotation'] = {'angle': ang, 'axis': 'z', 'origin': [8, 8, 8]}
        planes.append(e)
    planes.append({'from': [8, 0, 0], 'to': [8, 16, 16], 'shade': False, 'light_emission': 15,
                   'faces': {'east': fv, 'west': fv}})
    write(f'{ROOT}/models/item/light/beam_{lv}.json', {
        'textures': {'t': f'lighting:item/light/beam_{lv}', 'particle': f'lighting:item/light/beam_{lv}'}, 'elements': planes})

for name in list(PROFILES) + ['beam']:
    write(f'{ROOT}/items/{name}.json', {'model': {
        'type': 'minecraft:range_dispatch', 'property': 'minecraft:custom_model_data', 'index': 0,
        'entries': [{'threshold': lv, 'model': {'type': 'minecraft:model', 'model': f'lighting:item/light/{name}_{lv}',
                                                 'tints': tint}} for lv in range(LEVELS)],
        'fallback': {'type': 'minecraft:model', 'model': f'lighting:item/light/{name}_{LEVELS - 1}', 'tints': tint}}})

# canary for /lighting diagnose: a decal whose texture says PACK OK (drawn as a ring + check); if another pack
# overrides lighting:items/canary the player sees something else
CS = 64
cpx = []
for y in range(CS):
    for x in range(CS):
        r = math.hypot(x + 0.5 - CS / 2, y + 0.5 - CS / 2) / (CS / 2)
        ring = 1 - smooth(0.0, 0.07, abs(r - 0.8))
        # check mark: two segments
        def seg(ax, ay, bx, by):
            px_, py_ = x + 0.5, y + 0.5
            vx, vy = bx - ax, by - ay
            t = max(0, min(1, ((px_ - ax) * vx + (py_ - ay) * vy) / (vx * vx + vy * vy)))
            return math.hypot(px_ - ax - vx * t, py_ - ay - vy * t)
        check = 1 - smooth(2.0, 3.5, min(seg(18, 33, 28, 44), seg(28, 44, 47, 21)))
        a = max(ring, check, 0.25 * (1 - smooth(0, 0.8, r)))
        cpx.append((255, 255, 255, int(a * 255)))
png(f'{ROOT}/textures/item/light/canary.png', CS, CS, cpx)
fc = {'uv': [0, 0, 16, 16], 'texture': '#t', 'tintindex': 0}
write(f'{ROOT}/models/item/light/canary.json', {'textures': {'t': 'lighting:item/light/canary', 'particle': 'lighting:item/light/canary'},
      'elements': [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False, 'light_emission': 15,
                    'faces': {'north': fc, 'south': fc}}]})
write(f'{ROOT}/items/canary.json', {'model': {'type': 'minecraft:model', 'model': 'lighting:item/light/canary', 'tints': tint}})

# --- wand (unchanged look) and a handheld flashlight
W = [(0, 0, 0, 0)] * 256
def put(img, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16: img[y * 16 + x] = c
rod, rod_hi, band = (52, 38, 66, 255), (88, 66, 108, 255), (214, 168, 74, 255)
for i in range(2, 12):
    x, y = i, 15 - i
    put(W, x, y, rod); put(W, x + 1, y, rod_hi); put(W, x, y - 1, rod_hi if i % 2 else rod)
for i in (4, 8):
    put(W, i, 15 - i, band); put(W, i + 1, 15 - i, band); put(W, i, 14 - i, band)
for k, (x, y) in enumerate([(12, 3), (13, 2), (12, 2), (13, 3), (11, 3), (12, 4), (14, 2), (13, 1)]):
    put(W, x, y, (255, 255, 255, 255) if k < 2 else (150, 235, 255, 255) if k < 4 else (90, 170, 255, 255))
for x, y in ((11, 1), (15, 4), (14, 5), (10, 2)):
    put(W, x, y, (190, 230, 255, 160))
png(f'{ROOT}/textures/item/wand.png', 16, 16, W)
write(f'{ROOT}/models/item/wand.json', {'parent': 'minecraft:item/handheld', 'textures': {'layer0': 'lighting:item/wand'}})
write(f'{ROOT}/items/wand.json', {'model': {'type': 'minecraft:model', 'model': 'lighting:item/wand'}})

F = [(0, 0, 0, 0)] * 256
body, body_hi, rim, lens = (46, 50, 58, 255), (84, 92, 104, 255), (150, 156, 166, 255), (255, 246, 210, 255)
for i in range(3, 11):
    x, y = i, 15 - i
    put(F, x, y, body); put(F, x + 1, y, body_hi); put(F, x, y - 1, body)
for x, y in ((10, 4), (11, 4), (11, 3), (12, 3), (12, 4), (11, 5), (10, 3)):
    put(F, x, y, rim)
for x, y in ((12, 2), (13, 2), (13, 3), (13, 1), (14, 2)):
    put(F, x, y, lens)
put(F, 6, 8, (200, 60, 40, 255))
png(f'{ROOT}/textures/item/flashlight.png', 16, 16, F)
write(f'{ROOT}/models/item/flashlight.json', {'parent': 'minecraft:item/handheld', 'textures': {'layer0': 'lighting:item/flashlight'}})
write(f'{ROOT}/items/flashlight.json', {'model': {'type': 'minecraft:model', 'model': 'lighting:item/flashlight'}})
print('ok')
