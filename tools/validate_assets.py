#!/usr/bin/env python3
"""Valida os assets do mod sem abrir o jogo: JSON válido, item -> modelo -> textura existem, rotações de elemento
aceitas pelo Minecraft, UVs dentro de 0..16, receitas com ingredientes plausíveis, chaves de lang completas e
todo modelo referenciado por habilidade existe. Sai com código 1 se algo estiver errado."""
import glob
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RES = os.path.join(ROOT, "src", "main", "resources")
MOD = "agentsmod"
errors = []


def err(msg):
    errors.append(msg)


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        err(f"JSON inválido: {os.path.relpath(path, ROOT)}: {e}")
        return None


def res_path(kind, ref, ext):
    ns, p = ref.split(":") if ":" in ref else ("minecraft", ref)
    return os.path.join(RES, kind, ns, p + ext)


# 1) todo JSON
for p in glob.glob(os.path.join(RES, "**", "*.json"), recursive=True):
    load(p)

# 2) item definitions -> modelos -> texturas
for p in glob.glob(os.path.join(RES, "assets", MOD, "items", "**", "*.json"), recursive=True):
    d = load(p)
    if not d:
        continue
    m = d.get("model", {})
    ref = m.get("model")
    if ref and ref.startswith(MOD + ":"):
        mp = res_path("assets", ref, ".json").replace(os.sep + MOD + os.sep, os.sep + MOD + os.sep + "models" + os.sep, 1)
        if not os.path.exists(mp):
            err(f"{os.path.relpath(p, ROOT)} -> modelo ausente: {ref}")

VALID_ANGLES = {-45.0, -22.5, 0.0, 22.5, 45.0}
n_models = n_elements = 0
for p in glob.glob(os.path.join(RES, "assets", MOD, "models", "**", "*.json"), recursive=True):
    d = load(p)
    if not d:
        continue
    n_models += 1
    for key, ref in d.get("textures", {}).items():
        if ref.startswith("#"):
            continue
        tp = res_path("assets", ref, ".png").replace(os.sep + MOD + os.sep, os.sep + MOD + os.sep + "textures" + os.sep, 1)
        if not os.path.exists(tp):
            err(f"{os.path.relpath(p, ROOT)}: textura ausente {ref}")
    for i, e in enumerate(d.get("elements", [])):
        n_elements += 1
        for a, b in zip(e["from"], e["to"]):
            if not (-16 <= a <= 32 and -16 <= b <= 32):
                err(f"{os.path.relpath(p, ROOT)} elemento {i}: coordenada fora de -16..32")
        r = e.get("rotation")
        if r and float(r["angle"]) not in VALID_ANGLES:
            err(f"{os.path.relpath(p, ROOT)} elemento {i}: ângulo de rotação inválido {r['angle']}")
        for fname, f in e.get("faces", {}).items():
            uv = f.get("uv")
            if uv and not all(-0.01 <= v <= 16.01 for v in uv):
                err(f"{os.path.relpath(p, ROOT)} elemento {i} face {fname}: uv fora de 0..16 {uv}")

# 3) habilidades -> modelos, ícone e textos
agents = load(os.path.join(RES, "agentsmod", "agents.json"))
lang = {}
for code in ("pt_br", "en_us"):
    lang[code] = load(os.path.join(RES, "assets", MOD, "lang", code + ".json")) or {}
n_ab = 0
for a in (agents or {}).get("agents", []):
    for ab in a["abilities"]:
        n_ab += 1
        if not os.path.exists(os.path.join(RES, "assets", MOD, "textures", "gui", "ability", ab["id"] + ".png")):
            err(f"ícone de HUD ausente: {ab['id']}")
        if not os.path.exists(os.path.join(RES, "assets", MOD, "textures", "item", ab["id"] + ".png")):
            err(f"textura do item ausente: {ab['id']}")
        if not os.path.exists(os.path.join(RES, "data", MOD, "recipe", ab["id"] + ".json")):
            err(f"receita ausente: {ab['id']}")
        shape = ab.get("shape", "")
        if shape and not os.path.exists(os.path.join(RES, "assets", MOD, "items", "fx", f"{shape}_{a['id']}.json")):
            err(f"modelo de efeito ausente: {shape}_{a['id']}")
        for k in (f"ability.{MOD}.{ab['id']}", f"ability.{MOD}.{ab['id']}.desc", f"item.{MOD}.{ab['id']}"):
            for code, tbl in lang.items():
                if k not in tbl:
                    err(f"lang {code}: falta {k}")
for code, tbl in lang.items():
    for k in (f"item.{MOD}.reassignment_contract", f"message.{MOD}.contract_used", f"message.{MOD}.wrong_agent"):
        if k not in tbl:
            err(f"lang {code}: falta {k}")
for need in ("knife", "canister", "smoke_lump", "wind_spiral", "wind_streak", "shock_ring"):
    if not os.path.exists(os.path.join(RES, "assets", MOD, "items", "jett", need + ".json")):
        err(f"definição de item da Jett ausente: {need}")

# 4) receitas: sem duplicatas e <= 9 ingredientes
seen = {}
for p in glob.glob(os.path.join(RES, "data", MOD, "recipe", "*.json")):
    d = load(p)
    if not d:
        continue
    ing = d.get("ingredients", [])
    if len(ing) > 9:
        err(f"receita com mais de 9 ingredientes: {os.path.basename(p)}")
    key = tuple(sorted(ing))
    if key in seen:
        err(f"receita duplicada: {os.path.basename(p)} == {seen[key]}")
    seen[key] = os.path.basename(p)

print(f"modelos: {n_models}, elementos: {n_elements}, habilidades: {n_ab}, receitas: {len(seen)}")
if errors:
    print(f"{len(errors)} problema(s):")
    for e in errors[:60]:
        print(" -", e)
    sys.exit(1)
print("assets OK")
