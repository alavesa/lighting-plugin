"""Generates the Lighting pack assets that are pure data: the marker texture + its 64 slot models + the marker item
definition, and the wand texture. Run from the repo root: python3 tools/gen_pack.py"""
import json, math, os, struct, zlib

ROOT = 'resourcepack/assets/lighting'
SLOTS = 64

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

# --- marker: texel (x, y) = (217, 41, y*16 + x); kind = index / 64, slot = index % 64 (see lightdata.glsl)
png(f'{ROOT}/textures/item/marker.png', 16, 16, [(217, 41, i, 255) for i in range(256)])

def face(index, tint):
    x, y = index % 16, index // 16
    return {'uv': [x + 0.25, y + 0.25, x + 0.75, y + 0.75], 'texture': '#m', 'tintindex': tint}

for s in range(SLOTS):
    elements = []
    for kind, tint in ((0, 0), (1, 1), (2, 0)):
        f = face(kind * 64 + s, tint)
        # tiny cube at the item centre: every vertex is (almost) the light position; the shader moves them anyway
        elements.append({'from': [7.99, 7.99, 7.99], 'to': [8.01, 8.01, 8.01], 'shade': False,
                         'faces': {'north': f, 'south': f}})
    write(f'{ROOT}/models/item/marker/{s}.json', {'textures': {'m': 'lighting:item/marker', 'particle': 'lighting:item/marker'},
                                               'elements': elements})

tints = [{'type': 'minecraft:custom_model_data', 'index': 0, 'default': 0xFFFFFF},
         {'type': 'minecraft:custom_model_data', 'index': 1, 'default': 0}]
write(f'{ROOT}/items/marker.json', {'model': {
    'type': 'minecraft:range_dispatch', 'property': 'minecraft:custom_model_data', 'index': 0,
    'entries': [{'threshold': s, 'model': {'type': 'minecraft:model', 'model': f'lighting:item/marker/{s}', 'tints': tints}}
                for s in range(SLOTS)],
    'fallback': {'type': 'minecraft:model', 'model': 'lighting:item/marker/0', 'tints': tints}}})

# --- wand: a dark rod with brass bands and a glowing crystal tip, drawn on the item diagonal
W = [(0, 0, 0, 0)] * 256
def put(x, y, c):
    if 0 <= x < 16 and 0 <= y < 16: W[y * 16 + x] = c
rod, rod_hi, band = (52, 38, 66, 255), (88, 66, 108, 255), (214, 168, 74, 255)
for i in range(2, 12):          # bottom-left to top-right
    x, y = i, 15 - i
    put(x, y, rod); put(x + 1, y, rod_hi); put(x, y - 1, rod_hi if i % 2 else rod)
for i in (4, 8):
    put(i, 15 - i, band); put(i + 1, 15 - i, band); put(i, 14 - i, band)
core = [(12, 3), (13, 2), (12, 2), (13, 3), (11, 3), (12, 4), (14, 2), (13, 1)]
for k, (x, y) in enumerate(core):
    put(x, y, (255, 255, 255, 255) if k < 2 else (150, 235, 255, 255) if k < 4 else (90, 170, 255, 255))
for x, y in ((11, 1), (15, 4), (14, 5), (10, 2)):
    put(x, y, (190, 230, 255, 160))
png(f'{ROOT}/textures/item/wand.png', 16, 16, W)
write(f'{ROOT}/models/item/wand.json', {'parent': 'minecraft:item/handheld', 'textures': {'layer0': 'lighting:item/wand'}})
write(f'{ROOT}/items/wand.json', {'model': {'type': 'minecraft:model', 'model': 'lighting:item/wand'}})
print('ok')
