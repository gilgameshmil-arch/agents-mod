"""
Modelos 3D (multi-elemento, texturas pintadas) da Jett + ícones de HUD de alta resolução.
Chamado por generate_assets.py. Tudo aqui vira JSON de modelo Java + PNG; nada é "círculo colorido":
cada peça é um conjunto de elementos com UV próprio, textura pintada (aço escovado, couro trançado, energia, fumaça).
"""
import json
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

MOD = "agentsmod"


def _w(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def uvpx(x0, y0, x1, y1, size=32):
    k = 16.0 / size
    return [x0 * k, y0 * k, x1 * k, y1 * k]


def el(frm, to, side_uv, edge_uv=None, rot=None, glow=0, shade=True, tint=None):
    """Elemento. Faces norte/sul (planos grandes) usam side_uv; as demais usam edge_uv (ou side_uv)."""
    edge_uv = edge_uv or side_uv
    faces = {}
    for d in ("north", "south"):
        faces[d] = {"uv": side_uv, "texture": "#0"}
    for d in ("east", "west", "up", "down"):
        faces[d] = {"uv": edge_uv, "texture": "#0"}
    if tint is not None:
        for f in faces.values():
            f["tintindex"] = tint
    e = {"from": [round(v, 3) for v in frm], "to": [round(v, 3) for v in to], "faces": faces}
    if rot:
        axis, angle, origin = rot
        e["rotation"] = {"origin": list(origin), "axis": axis, "angle": angle}
    if glow:
        e["light_emission"] = glow
    if not shade:
        e["shade"] = False
    return e


def model(textures, elements, size=32, display=None):
    m = {"texture_size": [size, size], "textures": textures, "elements": elements}
    if display:
        m["display"] = display
    return m


# ----------------------------------------------------------------------------------------------------------------------
# Texturas
# ----------------------------------------------------------------------------------------------------------------------
def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def knife_texture():
    """32x32: [0..15,0..15] aço escovado | [16..31,0..3] gume | [0..15,16..31] cabo trançado |
    [16..31,4..15] metal escuro/dourado | [16..31,16..31] energia ciano."""
    rnd = random.Random(7)
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 255))
    px = img.load()
    for y in range(16):          # aço: degradê diagonal + riscos de escovado
        for x in range(16):
            t = (x + y) / 30
            base = lerp((120, 132, 148), (226, 236, 246), 0.5 + 0.5 * math.sin(t * math.pi * 2.0 - 0.6))
            n = rnd.randint(-9, 9) if (y % 2 == 0) else rnd.randint(-3, 3)
            px[x, y] = tuple(max(0, min(255, c + n)) for c in base) + (255,)
    for x in range(16, 32):      # gume brilhante
        for y in range(4):
            px[x, y] = lerp((235, 248, 255), (150, 190, 215), y / 3) + (255,)
    for y in range(16, 32):      # cabo: trançado diagonal escuro com fio ciano
        for x in range(16):
            band = ((x + y) // 3) % 2
            c = (34, 38, 46) if band else (22, 25, 31)
            if ((x - y) % 8) == 0:
                c = (60, 215, 235)
            px[x, y] = c + (255,)
    for y in range(4, 16):       # guarda/pomo: metal escuro com filete dourado
        for x in range(16, 32):
            c = lerp((52, 58, 70), (92, 100, 116), (x - 16) / 15)
            if y in (4, 15):
                c = (214, 178, 96)
            px[x, y] = c + (255,)
    for y in range(16, 32):      # energia: núcleo claro -> ciano
        for x in range(16, 32):
            t = abs((x - 16) - 7.5) / 8
            px[x, y] = lerp((235, 255, 255), (40, 200, 240), min(1, t * 1.2)) + (255,)
    return img


def canister_texture():
    rnd = random.Random(11)
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 255))
    px = img.load()
    for y in range(16):          # corpo: grafite com leve metalizado
        for x in range(16):
            base = lerp((58, 64, 76), (112, 120, 136), (x / 15))
            px[x, y] = tuple(max(0, min(255, c + rnd.randint(-5, 5))) for c in base) + (255,)
    for y in range(16, 32):      # faixa de energia ciano
        for x in range(16):
            t = abs(y - 24) / 8
            px[x, y] = lerp((220, 255, 255), (30, 170, 215), min(1, t)) + (255,)
    for y in range(16):          # tampas: aço claro com listras
        for x in range(16, 32):
            c = lerp((150, 158, 172), (214, 222, 234), x / 31)
            if y % 4 == 0:
                c = (90, 96, 108)
            px[x, y] = c + (255,)
    for y in range(16, 32):      # válvula/dourado
        for x in range(16, 32):
            px[x, y] = lerp((190, 150, 70), (240, 210, 130), (x - 16) / 15) + (255,)
    return img


def smoke_texture(tint=None):
    """Ruído suave translúcido (fumaça). Alfa maior no miolo, bordas esfumadas. tint=(r,g,b) tinge para o agente."""
    rnd = random.Random(23)
    big = Image.new("L", (8, 8))
    for y in range(8):
        for x in range(8):
            big.putpixel((x, y), rnd.randint(120, 255))
    big = big.resize((32, 32), Image.BICUBIC).filter(ImageFilter.GaussianBlur(1.2))
    img = Image.new("RGBA", (32, 32))
    for y in range(32):
        for x in range(32):
            v = big.getpixel((x, y))
            c = lerp((176, 204, 226), (246, 252, 255), v / 255)
            if tint:
                dark = tuple(int(t * 0.55) for t in tint)
                light = tuple(int(t * 0.55 + 255 * 0.45) for t in tint)
                c = lerp(dark, light, v / 255)
            img.putpixel((x, y), c + (int(120 + 100 * v / 255),))
    return img


def wind_texture():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    px = img.load()
    for y in range(32):
        for x in range(32):
            t = 1 - abs(y - 15.5) / 16
            a = int(255 * max(0, t) ** 1.4)
            px[x, y] = lerp((190, 245, 255), (255, 255, 255), t) + (a,)
    return img


def shock_texture():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    px = img.load()
    for y in range(32):
        for x in range(32):
            t = y / 31
            a = int(255 * (1 - t) ** 0.8)
            px[x, y] = lerp((255, 255, 255), (120, 225, 255), t) + (a,)
    return img


# ----------------------------------------------------------------------------------------------------------------------
# Modelos
# ----------------------------------------------------------------------------------------------------------------------
STEEL = uvpx(0, 0, 15, 15)
EDGE = uvpx(16, 0, 31, 3)
WRAP = uvpx(0, 16, 15, 31)
DARK = uvpx(16, 4, 31, 15)
ENERGY = uvpx(16, 16, 31, 31)



def slab(cx, y, cz, half_len, half_wid, thick, tangent_deg, uv, glow=10):
    """Lasca plana centrada em (cx,y,cz) cujo eixo longo segue 'tangent_deg' (graus no plano XZ, 0 = +X).
    O Minecraft só aceita rotação de elemento em -45..45 (passos de 22.5): escolhemos o eixo (x ou z) mais próximo
    e giramos o resto."""
    t = tangent_deg % 180.0
    if t >= 135.0:
        base, rem = 0, t - 180.0
    elif t >= 45.0:
        base, rem = 90, t - 90.0
    else:
        base, rem = 0, t
    rem = max(-45.0, min(45.0, round(rem / 22.5) * 22.5))
    if base == 0:
        frm = [cx - half_len, y, cz - half_wid]
        to = [cx + half_len, y + thick, cz + half_wid]
    else:
        frm = [cx - half_wid, y, cz - half_len]
        to = [cx + half_wid, y + thick, cz + half_len]
    # sentido do giro: ângulo no plano XZ cresce de +X para +Z; a rotação "y" do Minecraft gira no sentido oposto
    return el(frm, to, uv, uv, rot=("y", -rem, (cx, y, cz)) if rem else None, glow=glow, shade=False)

def knife_model():
    """Kunai/faca de arremesso da Jett: lâmina em folha (7 segmentos), nervura de energia, guarda, cabo trançado,
    anéis de aço, pomo em argola. Eixo +Y = ponta. Mede ~16 unidades (1 bloco) de ponta a pomo."""
    els = []
    prof = [0.40, 0.58, 0.78, 0.96, 1.08, 1.12, 1.08, 1.0, 0.88, 0.74, 0.58, 0.42, 0.26, 0.12]
    step = 12.0 / len(prof)
    for i, hw in enumerate(prof):
        y0 = 4.0 + i * step
        els.append(el([8 - hw, y0, 7.72], [8 + hw, y0 + step + 0.02, 8.28], STEEL, EDGE))
    els.append(el([7.86, 4.2, 8.28], [8.14, 14.2, 8.40], ENERGY, ENERGY, glow=15, shade=False))   # nervura frente
    els.append(el([7.86, 4.2, 7.60], [8.14, 14.2, 7.72], ENERGY, ENERGY, glow=15, shade=False))   # nervura costas
    els.append(el([6.0, 3.0, 7.4], [10.0, 4.0, 8.6], DARK, DARK))                                  # guarda
    els.append(el([6.4, 4.0, 7.55], [7.2, 4.5, 8.45], DARK, DARK))                                 # ombro esq.
    els.append(el([8.8, 4.0, 7.55], [9.6, 4.5, 8.45], DARK, DARK))                                 # ombro dir.
    els.append(el([7.3, -1.0, 7.3], [8.7, 3.0, 8.7], WRAP, WRAP))                                  # cabo
    for i in range(4):                                                                              # anéis do cabo
        y = -0.6 + i * 0.95
        els.append(el([7.15, y, 7.15], [8.85, y + 0.32, 8.85], DARK, DARK))
    els.append(el([7.0, -2.0, 7.0], [9.0, -1.0, 9.0], DARK, DARK))                                 # base do cabo
    # pomo em argola (4 barras) + gema de energia
    els.append(el([6.6, -3.6, 7.65], [9.4, -2.9, 8.35], DARK, DARK))
    els.append(el([6.6, -5.2, 7.65], [9.4, -4.5, 8.35], DARK, DARK))
    els.append(el([6.6, -5.2, 7.65], [7.3, -2.9, 8.35], DARK, DARK))
    els.append(el([8.7, -5.2, 7.65], [9.4, -2.9, 8.35], DARK, DARK))
    els.append(el([7.7, -4.2, 7.75], [8.3, -3.6, 8.25], ENERGY, ENERGY, glow=15, shade=False))
    return model({"0": f"{MOD}:item/jett/knife", "particle": f"{MOD}:item/jett/knife"}, els)


def canister_model():
    """Canister de fumaça: corpo octogonal (2 prismas girados), tampas, bocal, anel de energia, válvula e presilha."""
    els = []

    def octo(y0, y1, r, uv, glow=0):
        out = [el([8 - r, y0, 8 - r], [8 + r, y1, 8 + r], uv, uv, glow=glow, shade=(glow == 0))]
        out.append(el([8 - r, y0, 8 - r], [8 + r, y1, 8 + r], uv, uv, rot=("y", 45, (8, (y0 + y1) / 2, 8)),
                      glow=glow, shade=(glow == 0)))
        return out

    body = uvpx(0, 0, 15, 15)
    cap = uvpx(16, 0, 31, 15)
    ring = uvpx(0, 16, 15, 31)
    gold = uvpx(16, 16, 31, 31)
    els += octo(4.0, 12.0, 3.1, body)
    els += octo(3.2, 4.0, 3.5, cap)
    els += octo(12.0, 12.9, 3.5, cap)
    els += octo(7.2, 8.8, 3.35, ring, glow=15)                       # faixa de energia
    els += octo(12.9, 14.0, 1.8, cap)
    els.append(el([7.2, 14.0, 7.2], [8.8, 15.2, 8.8], gold, gold))   # válvula
    els.append(el([6.4, 14.9, 7.5], [9.6, 15.5, 8.5], gold, gold))   # alavanca
    els.append(el([9.2, 5.0, 7.3], [9.9, 11.0, 8.7], cap, cap))       # presilha lateral
    els.append(el([6.1, 5.0, 7.3], [6.8, 11.0, 8.7], cap, cap))
    return model({"0": f"{MOD}:item/jett/canister", "particle": f"{MOD}:item/jett/canister"}, els)


def smoke_lump_model(tex="smoke"):
    """Tufo de fumaça: miolo + 18 volumes menores em casca irregular (tamanhos/ângulos variados) => silhueta de
    nuvem, não de cubo. Translúcido e sem sombreamento."""
    uv = uvpx(0, 0, 31, 31)
    rnd = random.Random(41)
    els = [el([8 - 3.4, 8 - 3.0, 8 - 3.4], [8 + 3.4, 8 + 3.0, 8 + 3.4], uv, uv, shade=False)]
    els.append(el([8 - 3.4, 8 - 3.0, 8 - 3.4], [8 + 3.4, 8 + 3.0, 8 + 3.4], uv, uv, rot=("y", 45, (8, 8, 8)), shade=False))
    golden = math.pi * (3 - math.sqrt(5))
    n = 18
    for i in range(n):
        yy = 1 - 2 * (i + 0.5) / n
        rr = math.sqrt(1 - yy * yy)
        th = golden * i
        d = rnd.uniform(3.6, 4.5)
        cx, cy, cz = 8 + d * rr * math.cos(th), 8 + d * yy * 0.85, 8 + d * rr * math.sin(th)
        h = rnd.uniform(1.7, 2.9)
        ang = rnd.choice((-45, -22.5, 0, 22.5, 45))
        axis = rnd.choice(("y", "y", "x", "z"))
        els.append(el([cx - h, cy - h, cz - h], [cx + h, cy + h, cz + h], uv, uv,
                      rot=(axis, ang, (cx, cy, cz)) if ang else None, shade=False))
    return model({"0": f"{MOD}:item/jett/{tex}", "particle": f"{MOD}:item/jett/{tex}"}, els)


def wind_spiral_model(turns=1.7, height=14.5, r0=5.4, r1=2.4, segs=34):
    """Fita de vento em hélice: lascas tangentes à espiral (afinam para o topo)."""
    uv = uvpx(0, 0, 31, 31)
    els = []
    for i in range(segs):
        t = i / (segs - 1)
        ang = t * turns * 2 * math.pi
        r = r0 + (r1 - r0) * t
        cx, cz = 8 + r * math.cos(ang), 8 + r * math.sin(ang)
        tangent = math.degrees(ang) + 90.0
        w = 0.62 * (1 - 0.55 * t)
        L = 1.35 + 0.35 * (1 - t)
        els.append(slab(cx, 0.6 + t * height, cz, L, w, 0.5 * (1 - 0.4 * t), tangent, uv, glow=11))
    return model({"0": f"{MOD}:item/jett/wind", "particle": f"{MOD}:item/jett/wind"}, els)


def wind_streak_model():
    """Riscos de vento retos e paralelos (para o rastro do dash)."""
    uv = uvpx(0, 0, 31, 31)
    els = []
    rnd = random.Random(5)
    for i in range(7):
        x = 8 + rnd.uniform(-5, 5)
        y = 8 + rnd.uniform(-4, 4)
        L = rnd.uniform(7, 15)
        z0 = 8 - L / 2 + rnd.uniform(-1, 1)
        els.append(el([x - 0.45, y - 0.18, z0], [x + 0.45, y + 0.18, z0 + L], uv, uv, glow=12, shade=False))
    return model({"0": f"{MOD}:item/jett/wind", "particle": f"{MOD}:item/jett/wind"}, els)


def shock_ring_model(n=28, r=7.0):
    """Anel de onda de choque: lascas tangentes ao círculo (28 peças) + anel interno mais fino."""
    uv = uvpx(0, 0, 31, 31)
    els = []
    for k, (rad, hw, th) in enumerate(((r, 0.7, 0.8), (r - 1.5, 0.35, 0.4))):
        for i in range(n):
            a = 2 * math.pi * i / n
            cx, cz = 8 + rad * math.cos(a), 8 + rad * math.sin(a)
            els.append(slab(cx, 7.6 + k * 0.2, cz, 1.0 + 0.0 * k, hw, th, math.degrees(a) + 90.0, uv, glow=12))
    return model({"0": f"{MOD}:item/jett/shock", "particle": f"{MOD}:item/jett/shock"}, els)


# ----------------------------------------------------------------------------------------------------------------------
# Ícones de HUD (32x32, supersampling 4x para bordas suaves)
# ----------------------------------------------------------------------------------------------------------------------
def _poly(d, pts, fill):
    d.polygon(pts, fill=fill)


def icon_canvas(color):
    S = 128
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # fundo: cápsula escura com brilho do agente
    base = tuple(int(c * 0.20) for c in color)
    top = tuple(int(c * 0.55) for c in color)
    for y in range(S):
        c = lerp(top, base, y / (S - 1)) + (255,)
        d.line([(0, y), (S, y)], fill=c)
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([2, 2, S - 3, S - 3], radius=22, fill=255)
    img.putalpha(mask)
    return img


def draw_glyph(d, kind, color, S=128):
    hi = tuple(min(255, int(c * 0.35 + 255 * 0.65)) for c in color) + (255,)
    mid = tuple(color) + (255,)
    c = S // 2
    if kind == "tailwind":
        for i, off in enumerate((-26, 0, 26)):
            x = 30 + i * 4
            d.line([(x, c + off), (S - 36, c + off)], fill=mid, width=7)
            _poly(d, [(S - 34, c + off - 15), (S - 18, c + off), (S - 34, c + off + 15)], hi)
    elif kind == "updraft":
        _poly(d, [(c, 18), (c + 34, 62), (c + 13, 62), (c + 13, 108), (c - 13, 108), (c - 13, 62), (c - 34, 62)], hi)
        d.arc([c - 44, 70, c + 44, 120], 20, 160, fill=mid, width=7)
    elif kind == "cloudburst":
        for cx, cy, r in ((44, 74, 24), (68, 58, 30), (92, 76, 24), (66, 86, 26)):
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=hi)
        d.ellipse([20, 90, 108, 112], fill=mid)
    elif kind == "bladestorm":
        for k, ang in enumerate((-40, -20, 0, 20, 40)):
            a = math.radians(ang)
            x0, y0 = c + math.sin(a) * 10, 112 - math.cos(a) * 6
            x1, y1 = c + math.sin(a) * 78, 112 - math.cos(a) * 92
            d.line([(x0, y0), (x1, y1)], fill=hi, width=8)
            d.line([(x0, y0), (x0 + math.sin(a) * 18, y0 - math.cos(a) * 18)], fill=mid, width=12)
    elif kind == "DASH":
        for i in range(3):
            _poly(d, [(26 + i * 26, 30), (52 + i * 26, c), (26 + i * 26, S - 30), (36 + i * 26, c)], hi if i == 2 else mid)
    elif kind == "LEAP":
        _poly(d, [(c, 20), (c + 38, 66), (c + 14, 66), (c + 14, 108), (c - 14, 108), (c - 14, 66), (c - 38, 66)], hi)
    elif kind == "BLINK":
        d.ellipse([18, 34, 60, 94], outline=hi, width=8)
        d.ellipse([68, 34, 110, 94], outline=mid, width=8)
        d.line([(60, c), (68, c)], fill=hi, width=8)
    elif kind == "AREA":
        for ang in range(0, 360, 30):
            a = math.radians(ang)
            d.line([(c + math.cos(a) * 18, c + math.sin(a) * 18), (c + math.cos(a) * 50, c + math.sin(a) * 50)],
                   fill=hi if ang % 90 == 0 else mid, width=7)
        d.ellipse([c - 12, c - 12, c + 12, c + 12], fill=hi)
    elif kind == "ZONE":
        d.ellipse([20, 20, 108, 108], outline=mid, width=8)
        d.ellipse([44, 44, 84, 84], fill=hi)
    elif kind == "WALL":
        for i in range(4):
            x = 24 + i * 25
            _poly(d, [(x, 24), (x + 14, 24), (x + 14, 104), (x, 104)], hi if i % 2 == 0 else mid)
    elif kind == "BEAM":
        d.line([(24, 104), (104, 24)], fill=hi, width=9)
        d.ellipse([84, 14, 114, 44], outline=mid, width=7)
    elif kind == "MODE":
        d.ellipse([20, 20, 108, 108], outline=mid, width=7)
        d.line([(c, 8), (c, 120)], fill=hi, width=7)
        d.line([(8, c), (120, c)], fill=hi, width=7)


def hud_icon(path, color, kind):
    S = 128
    img = icon_canvas(color)
    d = ImageDraw.Draw(img)
    draw_glyph(d, kind, color)
    # filete interno claro
    d.rounded_rectangle([2, 2, S - 3, S - 3], radius=22, outline=tuple(color) + (255,), width=4)
    img = img.resize((32, 32), Image.LANCZOS)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


JETT_ICON_KIND = {"jett_tailwind": "tailwind", "jett_updraft": "updraft",
                  "jett_cloudburst": "cloudburst", "jett_blade_storm": "bladestorm"}


def emit(res_root, agents):
    tex = os.path.join(res_root, "assets", MOD, "textures", "item", "jett")
    os.makedirs(tex, exist_ok=True)
    knife_texture().save(os.path.join(tex, "knife.png"))
    canister_texture().save(os.path.join(tex, "canister.png"))
    smoke_texture().save(os.path.join(tex, "smoke.png"))
    wind_texture().save(os.path.join(tex, "wind.png"))
    shock_texture().save(os.path.join(tex, "shock.png"))
    mdir = os.path.join(res_root, "assets", MOD, "models", "item", "jett")
    idir = os.path.join(res_root, "assets", MOD, "items", "jett")
    models = {"knife": knife_model(), "canister": canister_model(), "smoke_lump": smoke_lump_model(),
              "wind_spiral": wind_spiral_model(), "wind_streak": wind_streak_model(), "shock_ring": shock_ring_model()}
    for name, m in models.items():
        _w(os.path.join(mdir, f"{name}.json"), m)
        _w(os.path.join(idir, f"{name}.json"), {"model": {"type": "minecraft:model", "model": f"{MOD}:item/jett/{name}"}})
    # fumaça tingida: um modelo por agente (smoke_lump_<id>), usada por toda habilidade de fumaça
    for agent in agents:
        c = ((agent["color"] >> 16) & 255, (agent["color"] >> 8) & 255, agent["color"] & 255)
        smoke_texture(c).save(os.path.join(tex, f'smoke_{agent["id"]}.png'))
        name = f'smoke_lump_{agent["id"]}'
        _w(os.path.join(mdir, f"{name}.json"), smoke_lump_model(f'smoke_{agent["id"]}'))
        _w(os.path.join(idir, f"{name}.json"), {"model": {"type": "minecraft:model", "model": f"{MOD}:item/jett/{name}"}})
    # ícones de HUD de TODAS as habilidades (usados no HUD novo e no loadout)
    for agent in agents:
        color = ((agent["color"] >> 16) & 255, (agent["color"] >> 8) & 255, agent["color"] & 255)
        for ab in agent["abilities"]:
            kind = JETT_ICON_KIND.get(ab["id"], ab["type"])
            hud_icon(os.path.join(res_root, "assets", MOD, "textures", "gui", "ability", f'{ab["id"]}.png'), color, kind)
    return sorted(models)
