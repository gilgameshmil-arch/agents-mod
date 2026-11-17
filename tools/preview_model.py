#!/usr/bin/env python3
"""Pré-visualização offline de modelos de item (JSON de elementos): rasteriza as faces com a textura real
(amostragem por UV, mapeamento bilinear do quad) e sombreamento simples. Uso:
  python3 tools/preview_model.py <saida.png> <modelo.json> [<modelo2.json> ...]
Gera 3 vistas (frente, lado, 3/4) por modelo. Serve para revisar o visual sem abrir o jogo."""
import json
import math
import os
import sys

from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources")


def load_tex(ref):
    ns, path = ref.split(":")
    p = os.path.join(RES, "assets", ns, "textures", path + ".png")
    return Image.open(p).convert("RGBA")


def rotate(pt, axis, ang, origin):
    x, y, z = (pt[i] - origin[i] for i in range(3))
    a = math.radians(ang)
    c, s = math.cos(a), math.sin(a)
    if axis == "y":
        x, z = x * c + z * s, -x * s + z * c
    elif axis == "x":
        y, z = y * c - z * s, y * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return (x + origin[0], y + origin[1], z + origin[2])


FACES = {
    "north": ((1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0), (0, 0, -1)),
    "south": ((0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1), (0, 0, 1)),
    "west": ((0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0), (-1, 0, 0)),
    "east": ((1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1), (1, 0, 0)),
    "up": ((0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1), (0, 1, 0)),
    "down": ((0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0), (0, -1, 0)),
}


def render(model, yaw, pitch, size=360):
    tex = load_tex(model["textures"]["0"])
    tw, th = tex.size
    quads = []
    for e in model["elements"]:
        f, t = e["from"], e["to"]
        rot = e.get("rotation")
        for name, face in e["faces"].items():
            c0, c1, c2, c3, nrm = FACES[name]
            pts = [tuple(f[i] if c[i] == 0 else t[i] for i in range(3)) for c in (c0, c1, c2, c3)]
            n = list(nrm)
            if rot:
                pts = [rotate(p, rot["axis"], rot["angle"], rot["origin"]) for p in pts]
                n = list(rotate(tuple(n), rot["axis"], rot["angle"], (0, 0, 0)))
            quads.append((pts, n, face["uv"], e.get("light_emission", 0) > 0))
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def view(p):
        x, y, z = p[0] - 8, p[1] - 8, p[2] - 8
        x, z = x * cy + z * sy, -x * sy + z * cy
        y, z = y * cp - z * sp, y * sp + z * cp
        return x, y, z

    img = Image.new("RGBA", (size, size), (28, 30, 38, 255))
    scale = size / 22.0
    items = []
    for pts, n, uv, glow in quads:
        vp = [view(p) for p in pts]
        nv = view((n[0] + 8, n[1] + 8, n[2] + 8))
        n0 = view((8, 8, 8))
        nn = (nv[0] - n0[0], nv[1] - n0[1], nv[2] - n0[2])
        if nn[2] > 0.001 and not glow:
            continue  # back-face
        depth = sum(v[2] for v in vp) / 4
        items.append((depth, vp, nn, uv, glow))
    items.sort(key=lambda it: -it[0])
    px = img.load()
    for depth, vp, nn, uv, glow in items:
        light = 1.0 if glow else 0.62 + 0.38 * max(0.0, -nn[1] * 0.6 + nn[0] * 0.4 - nn[2] * 0.5)
        sp2 = [(size / 2 + v[0] * scale, size / 2 - v[1] * scale) for v in vp]
        xs, ys = [p[0] for p in sp2], [p[1] for p in sp2]
        for y in range(max(0, int(min(ys))), min(size, int(max(ys)) + 1)):
            for x in range(max(0, int(min(xs))), min(size, int(max(xs)) + 1)):
                # coordenadas bilineares inversas aproximadas via triângulos
                for tri, uvtri in (((0, 1, 2), ((0, 0), (1, 0), (1, 1))), ((0, 2, 3), ((0, 0), (1, 1), (0, 1)))):
                    a, b, c = (sp2[i] for i in tri)
                    den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
                    if abs(den) < 1e-9:
                        continue
                    l1 = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / den
                    l2 = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / den
                    l3 = 1 - l1 - l2
                    if l1 < -0.01 or l2 < -0.01 or l3 < -0.01:
                        continue
                    u = l1 * uvtri[0][0] + l2 * uvtri[1][0] + l3 * uvtri[2][0]
                    v = l1 * uvtri[0][1] + l2 * uvtri[1][1] + l3 * uvtri[2][1]
                    tu = (uv[0] + (uv[2] - uv[0]) * u) / 16 * tw
                    tv = (uv[1] + (uv[3] - uv[1]) * v) / 16 * th
                    r, g, b2, a2 = tex.getpixel((min(tw - 1, max(0, int(tu))), min(th - 1, max(0, int(tv)))))
                    if a2 == 0:
                        break
                    base = px[x, y]
                    al = a2 / 255
                    col = tuple(int(min(255, c0 * light) * al + base[i] * (1 - al)) for i, c0 in enumerate((r, g, b2)))
                    px[x, y] = col + (255,)
                    break
    return img


def main():
    out = sys.argv[1]
    paths = sys.argv[2:]
    views = [(0, 0), (90, 0), (35, 22)]
    sheet = Image.new("RGBA", (360 * len(views), 360 * len(paths)))
    for r, p in enumerate(paths):
        m = json.load(open(p))
        for c, (yaw, pitch) in enumerate(views):
            sheet.paste(render(m, yaw, pitch), (c * 360, r * 360))
    sheet.save(out)
    print("ok", out)


if __name__ == "__main__":
    main()
