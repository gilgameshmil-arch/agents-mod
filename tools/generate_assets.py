#!/usr/bin/env python3
"""
Gera TUDO que depende da tabela de agentes do Agents Mod:
  - src/main/resources/agentsmod/agents.json      (lido pelo Java no servidor e no cliente)
  - modelos 3D de efeito (orb, dome, disc, ring, pillar, bolt, blade, cube, star, cross) + um item-definition por cor de agente
  - texturas dos ícones das habilidades (16x16) e dos efeitos
  - modelos/definições dos itens de desbloqueio, receitas de survival
  - lang en_us / pt_br (nomes, descrições geradas a partir dos parâmetros reais, GUI)

Para mudar um agente/habilidade, edite AGENTS abaixo e rode:  python3 tools/generate_assets.py
"""
import json
import math
import os
import re
import shutil
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jett_models

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "src", "main", "resources")
MOD = "agentsmod"
SLOTS = ["C", "Q", "E", "X"]


def E(effect, seconds, amp=0):
    return [effect, seconds, amp]


def A(slot, name, cd, type_, **p):
    return dict(slot=SLOTS.index(slot), name=name, cd=cd, type=type_, **p)


# ----------------------------------------------------------------------------------------------------------------------
# Tabela de agentes. Parâmetros por tipo (ver README):
#   DASH   speed ticks up          LEAP  up fwd            BLINK range
#   AREA   center(self|target) range throw radius facing effects ally_effects damage heal knock lift ignite
#   ZONE   center range throw radius duration follow effects ally_effects damage(dps) heal(hps) knock ignite size
#   WALL   range along width height duration block effects damage(dps) ignite
#   BEAM   range damage head aoe aoe_damage effects knock
#   MODE   ammo duration body_hits|body_dmg head_kill|head range aoe aoe_damage refill shot_cd ring effects
# Comuns: shape particle sound self_fx launch_up launch_fwd size
# ----------------------------------------------------------------------------------------------------------------------
AGENTS = [
    dict(id="jett", name="Jett", role="duelist", color=0x7FE0FF, sig="feather", particle="cloud", abilities=[
        A("C", "Tailwind", 6, "DASH", speed=2.4, ticks=5, vfx="tailwind"),
        A("Q", "Updraft", 8, "LEAP", up=1.05, fwd=0.0, vfx="updraft"),
        A("E", "Cloudburst", 15, "ZONE", center="target", range=24, throw=True, radius=3.2, duration=7.5, proj="canister", vfx="smoke"),
        A("X", "Blade Storm", 60, "MODE", ammo=5, duration=25, body_hits=3, head_kill=True, range=80, refill=True,
          shot_cd=5, vfx="knives", proj="knife", proj_speed=2.6),
    ]),
    dict(id="phoenix", name="Phoenix", role="duelist", color=0xFF7A2F, sig="blaze_powder", particle="flame", abilities=[
        A("C", "Blaze", 18, "WALL", along=True, width=9, height=2, duration=6, block="orange_stained_glass", damage=4, ignite=3),
        A("Q", "Curveball", 14, "AREA", center="target", range=20, throw=True, facing=True, radius=9,
          effects=[E("blindness", 2.0)], shape="orb", particle="flash"),
        A("E", "Hot Hands", 25, "ZONE", center="target", range=22, throw=True, radius=3.5, duration=6, damage=3, ignite=2, heal=3, shape="disc"),
        A("X", "Run It Back", 90, "AREA", center="self", radius=3, self_fx=[E("strength", 15), E("resistance", 15, 1), E("regeneration", 15, 1), E("speed", 15, 1)], shape="ring"),
    ]),
    dict(id="sage", name="Sage", role="sentinel", color=0x4DE3C1, sig="glistering_melon_slice", particle="happy_villager", abilities=[
        A("C", "Slow Orb", 18, "ZONE", center="target", range=20, throw=True, radius=4, duration=5, effects=[E("slowness", 2, 2)], shape="disc", particle="snowflake"),
        A("Q", "Healing Orb", 20, "AREA", center="self", radius=6, heal=12, ally_effects=[E("regeneration", 4, 1)], shape="cross", particle="heart"),
        A("E", "Barrier Orb", 30, "WALL", range=12, along=False, width=5, height=4, duration=20, block="packed_ice", particle="snowflake"),
        A("X", "Resurrection", 100, "AREA", center="self", radius=14, heal=40,
          ally_effects=[E("regeneration", 10, 2), E("absorption", 10, 1), E("resistance", 6, 1)], shape="star", particle="heart"),
    ]),
    dict(id="viper", name="Viper", role="controller", color=0x3BD16F, sig="fermented_spider_eye", particle="witch", abilities=[
        A("C", "Snake Bite", 16, "ZONE", center="target", range=24, throw=True, radius=3, duration=6, damage=4, effects=[E("weakness", 3)], shape="disc"),
        A("Q", "Poison Cloud", 18, "ZONE", center="target", range=24, throw=True, radius=4, duration=12, damage=3, effects=[E("weakness", 2)], vfx="smoke"),
        A("E", "Toxic Screen", 30, "WALL", along=True, width=11, height=3, duration=14, block="lime_stained_glass", damage=2),
        A("X", "Viper's Pit", 100, "ZONE", center="self", radius=10, duration=14, follow=False, damage=3,
          effects=[E("weakness", 3, 1), E("slowness", 3)], shape="dome"),
    ]),
    dict(id="brimstone", name="Brimstone", role="controller", color=0xE08A3C, sig="gunpowder", particle="flame", abilities=[
        A("C", "Stim Beacon", 20, "ZONE", center="target", range=22, throw=True, radius=5, duration=8,
          ally_effects=[E("speed", 3, 1), E("haste", 3, 1)], shape="disc", particle="end_rod"),
        A("Q", "Incendiary", 20, "ZONE", center="target", range=26, throw=True, radius=4, duration=7, damage=4, ignite=2, shape="disc"),
        A("E", "Sky Smoke", 28, "ZONE", center="target", range=40, throw=False, radius=4, duration=12, vfx="smoke"),
        A("X", "Orbital Strike", 100, "ZONE", center="target", range=60, throw=False, radius=5, duration=6, damage=9, ignite=3, shape="pillar"),
    ]),
    dict(id="omen", name="Omen", role="controller", color=0x7B4DFF, sig="ender_pearl", particle="portal", abilities=[
        A("C", "Shrouded Step", 12, "BLINK", range=12),
        A("Q", "Paranoia", 24, "AREA", center="target", range=14, throw=False, radius=6, effects=[E("blindness", 3)], shape="orb", particle="smoke"),
        A("E", "Dark Cover", 28, "ZONE", center="target", range=28, throw=True, radius=4, duration=10, vfx="smoke"),
        A("X", "From the Shadows", 100, "BLINK", range=60, self_fx=[E("invisibility", 3)]),
    ]),
    dict(id="killjoy", name="Killjoy", role="sentinel", color=0xF2D43A, sig="redstone_torch", particle="electric_spark", abilities=[
        A("C", "Nanoswarm", 18, "ZONE", center="target", range=20, throw=True, radius=3, duration=6, damage=6, shape="disc"),
        A("Q", "Alarmbot", 22, "ZONE", center="target", range=20, throw=False, radius=8, duration=12, effects=[E("glowing", 3), E("weakness", 3, 1)], size=1.0, shape="cube"),
        A("E", "Turret", 30, "ZONE", center="target", range=18, throw=False, radius=14, duration=20, damage=3, effects=[E("slowness", 2)], size=1.2, shape="cube"),
        A("X", "Lockdown", 110, "AREA", center="self", radius=14, effects=[E("slowness", 5, 4), E("weakness", 5, 1), E("mining_fatigue", 5, 2)], shape="dome"),
    ]),
    dict(id="cypher", name="Cypher", role="sentinel", color=0xE6E6E6, sig="tripwire_hook", particle="crit", abilities=[
        A("C", "Cyber Cage", 18, "ZONE", center="target", range=18, throw=True, radius=3, duration=7, effects=[E("slowness", 2, 3)], shape="cube"),
        A("Q", "Spycam", 25, "ZONE", center="target", range=30, throw=False, radius=16, duration=20, effects=[E("glowing", 2)], size=0.8, shape="cube"),
        A("E", "Trapwire", 25, "WALL", range=14, along=False, width=5, height=1, duration=30, block="cobweb", effects=[E("glowing", 6), E("slowness", 2, 1)]),
        A("X", "Neural Theft", 100, "AREA", center="self", radius=40, effects=[E("glowing", 6)], shape="ring", particle="enchanted_hit"),
    ]),
    dict(id="sova", name="Sova", role="initiator", color=0x4D7CFF, sig="arrow", particle="enchanted_hit", abilities=[
        A("C", "Owl Drone", 30, "ZONE", center="target", range=35, throw=False, radius=10, duration=10, effects=[E("glowing", 2)], size=0.9, shape="cube"),
        A("Q", "Shock Bolt", 14, "BEAM", range=60, damage=4, aoe=3, aoe_damage=6, shape="bolt"),
        A("E", "Recon Bolt", 28, "AREA", center="target", range=45, throw=True, radius=14, effects=[E("glowing", 4)], shape="ring"),
        A("X", "Hunter's Fury", 100, "MODE", ammo=3, duration=12, body_dmg=14, head=1, range=70, shot_cd=12, shape="bolt"),
    ]),
    dict(id="reyna", name="Reyna", role="duelist", color=0xC653E8, sig="purple_dye", particle="sculk_soul", abilities=[
        A("C", "Leer", 18, "AREA", center="target", range=24, throw=False, radius=3, effects=[E("blindness", 3)], shape="orb"),
        A("Q", "Devour", 1, "AREA", center="self", radius=1, needs_kill=True, heal=10, ally_effects=[E("regeneration", 3, 1)], shape="orb"),
        A("E", "Dismiss", 1, "DASH", speed=1.8, ticks=4, needs_kill=True, self_fx=[E("invisibility", 2)]),
        A("X", "Empress", 90, "AREA", center="self", radius=1, self_fx=[E("speed", 14, 1), E("strength", 14), E("regeneration", 14, 1)], shape="ring"),
    ]),
    dict(id="raze", name="Raze", role="duelist", color=0xFF9A2E, sig="fire_charge", particle="flame", abilities=[
        A("C", "Blast Pack", 10, "AREA", center="self", radius=3.5, damage=5, knock=1.3, lift=0.3, launch_up=0.9, launch_fwd=1.2, shape="orb", particle="explosion"),
        A("Q", "Paint Shells", 20, "AREA", center="target", range=22, throw=True, radius=4.5, damage=9, knock=0.8, shape="orb"),
        A("E", "Boom Bot", 30, "AREA", center="target", range=24, throw=True, radius=4, damage=12, knock=1.0, shape="cube", particle="explosion"),
        A("X", "Showstopper", 100, "MODE", ammo=3, duration=12, body_dmg=16, head=1, range=90, aoe=5, aoe_damage=10, shot_cd=15, shape="bolt"),
    ]),
    dict(id="breach", name="Breach", role="initiator", color=0xD9663A, sig="piston", particle="crit", abilities=[
        A("C", "Aftershock", 20, "AREA", center="target", range=18, throw=True, radius=3.5, damage=10, shape="orb"),
        A("Q", "Flashpoint", 22, "AREA", center="target", range=14, throw=True, facing=True, radius=10, effects=[E("blindness", 2.5)], shape="orb", particle="flash"),
        A("E", "Fault Line", 25, "AREA", center="target", range=22, throw=False, radius=5, effects=[E("slowness", 3, 2), E("nausea", 3)], lift=0.4, shape="disc"),
        A("X", "Rolling Thunder", 100, "AREA", center="target", range=30, throw=False, radius=12, effects=[E("slowness", 4, 3), E("nausea", 4)], lift=1.0, knock=0.6, shape="ring"),
    ]),
    dict(id="skye", name="Skye", role="initiator", color=0x8EDB6B, sig="oak_sapling", particle="happy_villager", abilities=[
        A("C", "Regrowth", 24, "ZONE", center="self", radius=6, duration=5, follow=True, heal=5, size=3.0, shape="cross"),
        A("Q", "Trailblazer", 20, "AREA", center="target", range=28, throw=True, radius=3, damage=4, effects=[E("slowness", 2, 3), E("nausea", 2)], shape="orb"),
        A("E", "Guiding Light", 22, "AREA", center="target", range=24, throw=True, facing=True, radius=9, effects=[E("blindness", 2)], shape="star", particle="flash"),
        A("X", "Seekers", 100, "AREA", center="self", radius=32, damage=8, effects=[E("nausea", 3), E("glowing", 4)], shape="ring"),
    ]),
    dict(id="yoru", name="Yoru", role="duelist", color=0x3F5BFF, sig="chorus_fruit", particle="portal", abilities=[
        A("C", "Fakeout", 20, "ZONE", center="target", range=22, throw=True, radius=6, duration=6, effects=[E("slowness", 1.5)], shape="star"),
        A("Q", "Blindside", 22, "AREA", center="target", range=16, throw=True, facing=True, radius=7, effects=[E("blindness", 2)], shape="star", particle="flash"),
        A("E", "Gatecrash", 25, "BLINK", range=35, particle="reverse_portal"),
        A("X", "Dimensional Drift", 100, "AREA", center="self", radius=2, self_fx=[E("invisibility", 10), E("speed", 10, 1)], shape="ring", particle="reverse_portal"),
    ]),
    dict(id="astra", name="Astra", role="controller", color=0xB06BFF, sig="glowstone_dust", particle="end_rod", abilities=[
        A("C", "Gravity Well", 25, "ZONE", center="target", range=30, throw=False, radius=5, duration=3, knock=-0.5, effects=[E("weakness", 2, 1)], shape="orb", particle="reverse_portal"),
        A("Q", "Nova Pulse", 25, "AREA", center="target", range=30, throw=False, radius=4, effects=[E("slowness", 2, 4), E("nausea", 2)], shape="orb"),
        A("E", "Nebula", 30, "ZONE", center="target", range=40, throw=False, radius=4, duration=10, vfx="smoke"),
        A("X", "Cosmic Divide", 110, "WALL", along=True, width=15, height=6, duration=12, block="purple_stained_glass"),
    ]),
    dict(id="kayo", name="KAY/O", role="initiator", color=0x58D6D6, sig="copper_ingot", particle="electric_spark", abilities=[
        A("C", "FLASH/drive", 16, "AREA", center="target", range=20, throw=True, facing=True, radius=9, effects=[E("blindness", 2.5)], shape="orb", particle="flash"),
        A("Q", "ZERO/point", 24, "AREA", center="target", range=26, throw=True, radius=5, damage=4, effects=[E("weakness", 6, 1), E("mining_fatigue", 6, 2)], shape="bolt"),
        A("E", "FRAG/ment", 28, "AREA", center="target", range=24, throw=True, radius=5, damage=14, knock=0.5, shape="cube", particle="explosion"),
        A("X", "NULL/cmd", 100, "AREA", center="self", radius=12, effects=[E("weakness", 8, 2), E("mining_fatigue", 8, 2), E("slowness", 8, 1)],
          self_fx=[E("speed", 10, 1), E("resistance", 10, 1)], shape="dome"),
    ]),
    dict(id="chamber", name="Chamber", role="sentinel", color=0xE5C46B, sig="gold_nugget", particle="firework", abilities=[
        A("C", "Trademark", 25, "ZONE", center="target", range=20, throw=True, radius=4, duration=30,
          effects=[E("slowness", 2, 2), E("glowing", 3)], shape="cube", size=0.9),
        A("Q", "Headhunter", 0, "MODE", gun=True, ammo=8, reload=50, duration=3600, body_dmg=7, head=3, range=70, shot_cd=8, shape="bolt"),
        A("E", "Rendezvous", 30, "BLINK", anchor=True, range=30, anchor_time=45, shape="disc"),
        A("X", "Tour de Force", 100, "MODE", ammo=5, duration=30, body_dmg=20, head_kill=True, range=100, shot_cd=26,
          kill_radius=7, kill_effects=[E("slowness", 4, 3)], shape="bolt"),
    ]),
    dict(id="neon", name="Neon", role="duelist", color=0x2E8BFF, sig="sugar", particle="electric_spark", abilities=[
        A("C", "Fast Lane", 20, "WALL", along=True, width=12, height=2, duration=6, block="cyan_stained_glass", effects=[E("slowness", 1, 1)]),
        A("Q", "Relay Bolt", 18, "AREA", center="target", range=22, throw=True, radius=4, effects=[E("slowness", 2, 3)], lift=0.5, shape="orb"),
        A("E", "High Gear", 15, "AREA", center="self", radius=1, self_fx=[E("speed", 7, 2), E("jump_boost", 7, 1)], shape="ring"),
        A("X", "Overdrive", 100, "MODE", ammo=24, duration=12, body_dmg=3, head=1.5, range=40, shot_cd=3, shape="bolt", self_fx=[E("speed", 12, 1)]),
    ]),
    dict(id="fade", name="Fade", role="initiator", color=0x6B4E9E, sig="ink_sac", particle="sculk_soul", abilities=[
        A("C", "Prowler", 24, "ZONE", center="target", range=35, throw=False, radius=8, duration=6, effects=[E("glowing", 2), E("slowness", 2, 1)], shape="orb"),
        A("Q", "Seize", 26, "ZONE", center="target", range=24, throw=True, radius=4.5, duration=4, effects=[E("slowness", 2, 4)], shape="disc"),
        A("E", "Haunt", 28, "AREA", center="target", range=32, throw=True, radius=12, effects=[E("glowing", 4)], shape="ring"),
        A("X", "Nightfall", 100, "AREA", center="target", range=40, throw=False, radius=16, effects=[E("darkness", 5), E("slowness", 5, 1), E("glowing", 5)], shape="ring"),
    ]),
    dict(id="harbor", name="Harbor", role="controller", color=0x2AA7A0, sig="prismarine_shard", particle="bubble_pop", abilities=[
        A("C", "Cascade", 18, "WALL", along=True, width=8, height=3, duration=3, block="light_blue_stained_glass", effects=[E("slowness", 1, 1)]),
        A("Q", "Cove", 25, "ZONE", center="target", range=24, throw=True, radius=3, duration=12, shape="dome"),
        A("E", "High Tide", 30, "WALL", along=True, width=12, height=3, duration=7, block="blue_stained_glass", effects=[E("slowness", 2, 2)]),
        A("X", "Reckoning", 100, "ZONE", center="target", range=40, throw=False, radius=8, duration=8, damage=4, effects=[E("slowness", 2, 3)], shape="disc"),
    ]),
    dict(id="gekko", name="Gekko", role="initiator", color=0xA6E83A, sig="slime_ball", particle="happy_villager", abilities=[
        A("C", "Mosh Pit", 20, "AREA", center="target", range=22, throw=True, radius=4.5, damage=12, shape="orb", particle="explosion"),
        A("Q", "Wingman", 24, "AREA", center="target", range=30, throw=True, radius=3, effects=[E("slowness", 2, 4), E("nausea", 2)], shape="cube"),
        A("E", "Dizzy", 22, "AREA", center="target", range=26, throw=True, facing=True, radius=12, effects=[E("blindness", 3)], shape="star", particle="flash"),
        A("X", "Thrash", 100, "AREA", center="target", range=35, throw=True, radius=5, damage=6, effects=[E("slowness", 5, 4), E("nausea", 5)], shape="orb"),
    ]),
    dict(id="deadlock", name="Deadlock", role="sentinel", color=0xB8C4D6, sig="cobweb", particle="ash", abilities=[
        A("C", "GravNet", 20, "AREA", center="target", range=22, throw=True, radius=6, effects=[E("slowness", 3, 3), E("nausea", 3)], shape="disc"),
        A("Q", "Sonic Sensor", 24, "ZONE", center="target", range=24, throw=True, radius=8, duration=15, effects=[E("glowing", 2)], size=0.6, shape="cube"),
        A("E", "Barrier Mesh", 30, "WALL", range=12, along=False, width=7, height=2, duration=14, block="iron_bars"),
        A("X", "Annihilation", 110, "AREA", center="target", range=36, throw=True, radius=4, damage=14, effects=[E("slowness", 5, 4)], shape="cube"),
    ]),
    dict(id="iso", name="Iso", role="duelist", color=0xC24BD6, sig="quartz", particle="enchanted_hit", abilities=[
        A("C", "Undercut", 18, "BEAM", range=30, damage=2, effects=[E("weakness", 5, 1), E("slowness", 3, 1)], shape="bolt"),
        A("Q", "Double Tap", 25, "AREA", center="self", radius=1, self_fx=[E("absorption", 8, 1), E("haste", 8, 1)], shape="orb"),
        A("E", "Contingency", 30, "WALL", along=True, width=7, height=3, duration=6, block="purple_stained_glass"),
        A("X", "Kill Contract", 100, "ZONE", center="target", range=26, throw=False, radius=8, duration=12,
          effects=[E("weakness", 3, 1), E("slowness", 3, 1)], self_fx=[E("strength", 12, 1), E("absorption", 12, 2)], shape="dome"),
    ]),
    dict(id="clove", name="Clove", role="controller", color=0xFF8FD1, sig="bone_meal", particle="heart", abilities=[
        A("C", "Pick-me-up", 1, "AREA", center="self", radius=1, needs_kill=True, self_fx=[E("absorption", 10, 2), E("speed", 6, 1)], shape="orb"),
        A("Q", "Meddle", 20, "AREA", center="target", range=22, throw=True, radius=4, damage=5, effects=[E("weakness", 4, 1)], shape="orb"),
        A("E", "Ruse", 8, "ZONE", center="target", range=26, throw=False, radius=4, duration=13, vfx="smoke"),
        A("X", "Not Dead Yet", 100, "AREA", center="self", radius=1, passive=True, ndy=True, ndy_time=12,
          self_fx=[E("regeneration", 8, 2), E("resistance", 8, 1)], shape="star"),
    ]),
    dict(id="vyse", name="Vyse", role="sentinel", color=0x9FD4E8, sig="vine", particle="crit", abilities=[
        A("C", "Razorvine", 20, "ZONE", center="target", range=20, throw=False, radius=3.5, duration=10, damage=4, effects=[E("slowness", 2, 1)], shape="disc"),
        A("Q", "Shear", 24, "WALL", range=12, along=False, width=7, height=3, duration=6, block="glass_pane", damage=6, effects=[E("slowness", 2, 2)]),
        A("E", "Arc Rose", 22, "AREA", center="target", range=22, throw=True, facing=True, radius=9, effects=[E("blindness", 2)], shape="star", particle="flash"),
        A("X", "Steel Garden", 100, "AREA", center="self", radius=18, effects=[E("slowness", 6, 3), E("mining_fatigue", 6, 2), E("weakness", 6, 1)], shape="dome"),
    ]),
    dict(id="tejo", name="Tejo", role="initiator", color=0xFF6B6B, sig="iron_nugget", particle="poof", abilities=[
        A("C", "Stealth Drone", 30, "ZONE", center="target", range=40, throw=False, radius=8, duration=10, effects=[E("glowing", 2), E("weakness", 2)], size=0.9, shape="cube"),
        A("Q", "Special Delivery", 22, "AREA", center="target", range=26, throw=True, radius=4, effects=[E("slowness", 3, 4), E("nausea", 3)], shape="orb"),
        A("E", "Guided Salvo", 30, "AREA", center="target", range=45, throw=False, radius=5, damage=10, knock=0.7, shape="cube", particle="explosion"),
        A("X", "Armageddon", 110, "ZONE", center="target", range=50, throw=False, radius=12, duration=5, damage=6, shape="ring", particle="explosion"),
    ]),
    dict(id="waylay", name="Waylay", role="duelist", color=0xFF5FB8, sig="snowball", particle="glow", abilities=[
        A("C", "Saturate", 20, "AREA", center="target", range=22, throw=True, radius=4, effects=[E("slowness", 3, 2), E("weakness", 3)], shape="orb"),
        A("Q", "Lightspeed", 12, "DASH", speed=2.2, ticks=8, self_fx=[E("speed", 4, 1)]),
        A("E", "Refract", 20, "BLINK", range=10, self_fx=[E("speed", 3, 1)]),
        A("X", "Convergent Paths", 100, "AREA", center="target", range=30, throw=False, radius=14, effects=[E("slowness", 4, 3), E("nausea", 4)], knock=0.5, shape="ring"),
    ]),
    dict(id="veto", name="Veto", role="sentinel", color=0x8A8F99, sig="coal", particle="smoke", abilities=[
        A("C", "Crosscut", 18, "BLINK", range=16),
        A("Q", "Chokehold", 22, "ZONE", center="target", range=22, throw=True, radius=4, duration=4, effects=[E("slowness", 2, 4), E("weakness", 2, 1)], shape="disc"),
        A("E", "Interceptor", 30, "ZONE", center="self", radius=8, duration=10, follow=True, ally_effects=[E("resistance", 2, 1)], shape="dome", particle="crit"),
        A("X", "Evolution", 90, "AREA", center="self", radius=1, self_fx=[E("strength", 25, 1), E("regeneration", 25, 1), E("resistance", 25, 1), E("speed", 25, 1)], shape="ring"),
    ]),
    dict(id="miks", name="Miks", role="controller", color=0xFF4D8B, sig="note_block", particle="note", abilities=[
        A("C", "M-Pulse", 20, "ZONE", center="target", range=22, throw=True, radius=5, duration=6, heal=2, effects=[E("slowness", 2, 1), E("nausea", 2)], shape="disc"),
        A("Q", "Harmonize", 25, "AREA", center="self", radius=12, ally_effects=[E("speed", 8, 1), E("strength", 8)], shape="ring"),
        A("E", "Waveform", 28, "ZONE", center="target", range=30, throw=False, radius=4, duration=8, vfx="smoke"),
        A("X", "Bassquake", 100, "AREA", center="target", range=20, throw=False, radius=9, effects=[E("slowness", 5, 3), E("weakness", 5, 1), E("nausea", 5)], knock=1.5, shape="ring", particle="sonic_boom"),
    ]),
    dict(id="hush", name="Hush", role="duelist", color=0xE8A6FF, sig="phantom_membrane", particle="end_rod", abilities=[
        A("C", "Flow", 12, "LEAP", up=0.75, fwd=1.3),
        A("Q", "Flashbulb", 20, "AREA", center="target", range=22, throw=True, facing=True, radius=10, effects=[E("blindness", 2.5)], shape="orb", particle="flash"),
        A("E", "Displacement", 24, "AREA", center="target", range=24, throw=True, radius=6, knock=-1.4, effects=[E("slowness", 2, 2), E("nausea", 2)], shape="orb", particle="reverse_portal"),
        A("X", "Suspension", 100, "AREA", center="target", range=40, throw=True, radius=6, effects=[E("levitation", 4, 1)], lift=0.4, shape="orb"),
    ]),
]

ROLES = {
    "duelist": ("Duelista", "Duelist"),
    "initiator": ("Iniciador", "Initiator"),
    "controller": ("Controlador", "Controller"),
    "sentinel": ("Sentinela", "Sentinel"),
}

# Itens de tier por slot: (item, quantidade) somados a 2x (3x no X) o item "assinatura" do agente.
TIERS = {
    0: [("iron_ingot", 1)],
    1: [("gold_ingot", 1), ("redstone", 1)],
    2: [("emerald", 1), ("lapis_lazuli", 1)],
    3: [("diamond", 2), ("ender_eye", 1)],
}

DEFAULT_SOUND = {
    "DASH": "entity.breeze.slide", "LEAP": "entity.wind_charge.wind_burst", "BLINK": "entity.enderman.teleport",
    "AREA": "entity.firework_rocket.blast", "ZONE": "block.beacon.activate", "WALL": "block.glass.place",
    "BEAM": "entity.arrow.shoot", "MODE": "block.beacon.activate",
}


def slug(text):
    return re.sub(r"_+", "_", re.sub(r"[^a-z0-9]+", "_", text.lower())).strip("_")


def mix(c, t, f):
    return tuple(int(c[i] * (1 - f) + t[i] * f) for i in range(3))


def rgb(n):
    return ((n >> 16) & 255, (n >> 8) & 255, n & 255)


# ----------------------------------------------------------------------------------------------------------------------
# Normaliza a tabela e valida
# ----------------------------------------------------------------------------------------------------------------------
def normalize():
    seen_ids, seen_recipes = set(), set()
    for ai, agent in enumerate(AGENTS):
        assert len(agent["abilities"]) == 4, agent["id"]
        agent["index"] = ai
        for ab in agent["abilities"]:
            ab["id"] = f'{agent["id"]}_{slug(ab["name"])}'
            assert ab["id"] not in seen_ids, ab["id"]
            seen_ids.add(ab["id"])
            ab["cd_ticks"] = int(round(ab["cd"] * 20))
            t = ab["type"]
            ab.setdefault("particle", agent["particle"])
            ab.setdefault("sound", DEFAULT_SOUND[t])
            if ab.get("vfx") == "smoke":
                ab.setdefault("smoke", agent["id"])
            if t == "MODE":
                ab.setdefault("shape", "bolt")
            if t == "BEAM":
                ab.setdefault("shape", "bolt")
            ab.setdefault("shape", "")
            recipe = [(agent["sig"], 3 if ab["slot"] == 3 else 2)] + TIERS[ab["slot"]]
            key = tuple(sorted(recipe))
            assert key not in seen_recipes, ("receita duplicada", ab["id"])
            seen_recipes.add(key)
            ab["recipe"] = [[f"minecraft:{i}", n] for i, n in recipe]


# ----------------------------------------------------------------------------------------------------------------------
# agents.json
# ----------------------------------------------------------------------------------------------------------------------
def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def emit_agents_json():
    out = {"agents": []}
    for agent in AGENTS:
        out["agents"].append({
            "id": agent["id"], "role": agent["role"], "color": agent["color"], "name": agent["name"],
            "abilities": agent["abilities"],
        })
    write_json(os.path.join(RES, "agentsmod", "agents.json"), out)


# ----------------------------------------------------------------------------------------------------------------------
# Modelos 3D de efeito (elementos de bloco, centrados em (8,8,8), raio 8 = meio bloco na escala 1)
# ----------------------------------------------------------------------------------------------------------------------
def face(tex="#0"):
    return {"uv": [0, 0, 16, 16], "texture": tex, "tintindex": 0}


def box(x1, y1, z1, x2, y2, z2, rot=0):
    el = {"from": [round(x1, 3), round(y1, 3), round(z1, 3)], "to": [round(x2, 3), round(y2, 3), round(z2, 3)],
          "faces": {d: face() for d in ("north", "east", "south", "west", "up", "down")}}
    if rot:
        el["rotation"] = {"origin": [8, (y1 + y2) / 2, 8], "axis": "y", "angle": rot}
    return el


def disc(y, r, thick=1.0, jitter=0.0):
    """Disco aproximado por 4 quadrados girados (0, 22.5, 45, -22.5 graus)."""
    a = max(0.6, r * 1.35) / 2
    return [box(8 - a, y - thick / 2 + jitter * i, 8 - a, 8 + a, y + thick / 2 + jitter * i, 8 + a, rot)
            for i, rot in enumerate((0, 22.5, 45, -22.5))]


def shape_orb():
    els = []
    for h in (-6.5, -4.5, -2.2, 0, 2.2, 4.5, 6.5):
        r = math.sqrt(max(0.0, 64 - h * h))
        els += disc(8 + h, r, 1.6, 0.004)
    return els


def shape_dome():
    els = []
    for h in (0, 2.0, 4.0, 5.8, 7.2):
        r = math.sqrt(max(0.0, 64 - h * h))
        els += disc(8 + h - 6 + 6, r, 1.6, 0.004)
    return [e for e in els]


def shape_disc():
    return disc(8, 8, 1.2, 0.01) + disc(8.8, 5.5, 0.8, 0.01)


def shape_ring():
    els = []
    for k in range(16):
        ang = k * 22.5
        cx = 8 + 6.6 * math.cos(math.radians(ang))
        cz = 8 + 6.6 * math.sin(math.radians(ang))
        s = 1.35
        els.append(box(cx - s, 7, cz - s, cx + s, 9, cz + s, rot=0))
    return els


def shape_pillar():
    return [box(6.5, 0, 6.5, 9.5, 16, 9.5), box(4.5, 0, 4.5, 11.5, 16, 11.5)]


def shape_bolt():
    return [box(7.2, 2, 7.2, 8.8, 12, 8.8), box(7.6, 12, 7.6, 8.4, 15, 8.4), box(6.4, 1, 7.6, 9.6, 4, 8.4),
            box(7.6, 1, 6.4, 8.4, 4, 9.6)]


def shape_blade():
    return [box(7.1, 4, 7.4, 8.9, 14, 8.6), box(7.5, 14, 7.5, 8.5, 16, 8.5), box(5.5, 2.6, 7.2, 10.5, 4, 8.8),
            box(7.4, -1, 7.4, 8.6, 2.6, 8.6), box(7.1, -2, 7.1, 8.9, -1, 8.9)]


def shape_cube():
    return [box(3, 3, 3, 13, 13, 13), box(1.5, 1.5, 1.5, 14.5, 14.5, 14.5)]


def shape_star():
    return [box(0, 7, 7, 16, 9, 9), box(7, 0, 7, 9, 16, 9), box(7, 7, 0, 9, 9, 16), box(5.5, 5.5, 5.5, 10.5, 10.5, 10.5)]


def shape_cross():
    return [box(2, 5.5, 6, 14, 10.5, 10), box(5.5, 2, 6, 10.5, 14, 10)]


SHAPES = {
    "orb": (shape_orb, "glass"), "dome": (shape_dome, "glass"), "disc": (shape_disc, "glass"),
    "ring": (shape_ring, "glass"), "pillar": (shape_pillar, "glass"), "bolt": (shape_bolt, "solid"),
    "blade": (shape_blade, "solid"), "cube": (shape_cube, "glass"), "star": (shape_star, "glass"),
    "cross": (shape_cross, "glass"),
}


def emit_fx():
    tex_dir = os.path.join(RES, "assets", MOD, "textures", "item")
    os.makedirs(tex_dir, exist_ok=True)
    glass = Image.new("RGBA", (16, 16))
    solid = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            d = min(x, y, 15 - x, 15 - y)
            glass.putpixel((x, y), (255, 255, 255, 120 + min(d, 4) * 14))
            solid.putpixel((x, y), (255, 255, 255, 255) if d > 0 else (215, 215, 215, 255))
    glass.save(os.path.join(tex_dir, "fx_glass.png"))
    solid.save(os.path.join(tex_dir, "fx_solid.png"))
    for name, (fn, kind) in SHAPES.items():
        model = {"credit": "Efeito 3D do Agents Mod (gerado por tools/generate_assets.py)",
                 "textures": {"0": f"{MOD}:item/fx_{kind}", "particle": f"{MOD}:item/fx_{kind}"},
                 "elements": fn()}
        write_json(os.path.join(RES, "assets", MOD, "models", "item", "fx", f"{name}.json"), model)
        for agent in AGENTS:
            write_json(os.path.join(RES, "assets", MOD, "items", "fx", f'{name}_{agent["id"]}.json'), {
                "model": {"type": "minecraft:model", "model": f"{MOD}:item/fx/{name}",
                          "tints": [{"type": "minecraft:constant", "value": agent["color"]}]}})


# ----------------------------------------------------------------------------------------------------------------------
# Ícones das habilidades (16x16)
# ----------------------------------------------------------------------------------------------------------------------
LETTERS = {
    "C": [".##", "#..", "#..", "#..", ".##"],
    "Q": [".#.", "#.#", "#.#", "#.#", ".##"],
    "E": ["###", "#..", "##.", "#..", "###"],
    "X": ["#.#", "#.#", ".#.", "#.#", "#.#"],
}


def glyph(d, t, col):
    if t == "DASH":
        for x in (3, 6, 9):
            d.line([(x, 4), (x + 3, 8), (x, 12)], fill=col, width=1)
    elif t == "LEAP":
        d.line([(8, 13), (8, 3)], fill=col)
        d.line([(4, 7), (8, 3), (12, 7)], fill=col)
    elif t == "BLINK":
        d.ellipse([2, 6, 6, 10], outline=col)
        d.ellipse([9, 6, 13, 10], outline=col)
        d.point([(7, 8), (8, 8)], fill=col)
    elif t == "AREA":
        for ang in range(0, 360, 45):
            x1 = 8 + 2 * math.cos(math.radians(ang))
            y1 = 8 + 2 * math.sin(math.radians(ang))
            x2 = 8 + 6 * math.cos(math.radians(ang))
            y2 = 8 + 6 * math.sin(math.radians(ang))
            d.line([(x1, y1), (x2, y2)], fill=col)
    elif t == "ZONE":
        d.ellipse([3, 3, 12, 12], outline=col)
        d.ellipse([6, 6, 9, 9], fill=col)
    elif t == "WALL":
        for x in (3, 6, 9, 12):
            d.line([(x, 3), (x, 12)], fill=col)
    elif t == "BEAM":
        d.line([(3, 13), (12, 4)], fill=col)
        d.rectangle([11, 3, 13, 5], fill=col)
    elif t == "MODE":
        d.ellipse([3, 3, 12, 12], outline=col)
        d.line([(8, 1), (8, 14)], fill=col)
        d.line([(1, 8), (14, 8)], fill=col)


def emit_icons():
    out_dir = os.path.join(RES, "assets", MOD, "textures", "item")
    for agent in AGENTS:
        base = rgb(agent["color"])
        top, bot = mix(base, (0, 0, 0), 0.45), mix(base, (0, 0, 0), 0.82)
        hi = mix(base, (255, 255, 255), 0.7)
        for ab in agent["abilities"]:
            img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
            px = img.load()
            for y in range(16):
                for x in range(16):
                    f = y / 15
                    px[x, y] = mix(top, bot, f) + (255,)
            for x in range(16):
                for y in range(16):
                    if x in (0, 15) or y in (0, 15):
                        px[x, y] = base + (255,)
            for cx, cy in ((0, 0), (15, 0), (0, 15), (15, 15)):
                px[cx, cy] = (0, 0, 0, 0)
            d = ImageDraw.Draw(img)
            glyph(d, ab["type"], hi + (255,))
            for ry, row in enumerate(LETTERS[SLOTS[ab["slot"]]]):
                for rx, ch in enumerate(row):
                    if ch == "#":
                        px[11 + rx, 9 + ry] = (255, 255, 255, 255)
            img.save(os.path.join(out_dir, f'{ab["id"]}.png'))


# ----------------------------------------------------------------------------------------------------------------------
# Itens de desbloqueio + receitas
# ----------------------------------------------------------------------------------------------------------------------
def emit_items_and_recipes():
    for agent in AGENTS:
        for ab in agent["abilities"]:
            write_json(os.path.join(RES, "assets", MOD, "models", "item", f'{ab["id"]}.json'),
                       {"parent": "minecraft:item/generated", "textures": {"layer0": f'{MOD}:item/{ab["id"]}'}})
            write_json(os.path.join(RES, "assets", MOD, "items", f'{ab["id"]}.json'),
                       {"model": {"type": "minecraft:model", "model": f'{MOD}:item/{ab["id"]}'}})
            ingredients = []
            for item, n in ab["recipe"]:
                ingredients += [item] * n
            assert len(ingredients) <= 9, ab["id"]
            write_json(os.path.join(RES, "data", MOD, "recipe", f'{ab["id"]}.json'), {
                "type": "minecraft:crafting_shapeless", "category": "misc", "ingredients": ingredients,
                "result": {"id": f'{MOD}:{ab["id"]}', "count": 1}})


# ----------------------------------------------------------------------------------------------------------------------
# Textos (descrições geradas dos parâmetros reais)
# ----------------------------------------------------------------------------------------------------------------------
EFF = {
    "slowness": ("Lentidão", "Slowness"), "speed": ("Velocidade", "Speed"), "blindness": ("Cegueira", "Blindness"),
    "weakness": ("Fraqueza", "Weakness"), "poison": ("Veneno", "Poison"), "regeneration": ("Regeneração", "Regeneration"),
    "absorption": ("Absorção", "Absorption"), "resistance": ("Resistência", "Resistance"), "strength": ("Força", "Strength"),
    "jump_boost": ("Super pulo", "Jump Boost"), "levitation": ("Levitação", "Levitation"),
    "glowing": ("Revelado (brilho)", "Revealed (glowing)"), "nausea": ("Náusea", "Nausea"),
    "darkness": ("Escuridão", "Darkness"), "invisibility": ("Invisibilidade", "Invisibility"),
    "mining_fatigue": ("Fadiga", "Mining Fatigue"), "haste": ("Pressa", "Haste"), "slow_falling": ("Queda lenta", "Slow Falling"),
}
ROMAN = ["", " II", " III", " IV", " V", " VI"]


def g(x):
    return f"{x:g}"


def fx_text(effs, lang):
    idx = 0 if lang == "pt" else 1
    parts = []
    for eid, sec, amp in effs:
        s = g(sec).replace(".", ",") if lang == "pt" else g(sec)
        parts.append(f"{EFF[eid][idx]}{ROMAN[min(amp, 5)]} {s}s")
    return ", ".join(parts)


def describe(ab, lang):
    pt = lang == "pt"
    t = ab["type"]
    out = []
    dec = (lambda s: s.replace(".", ",")) if pt else (lambda s: s)

    def foes(zone):
        parts = []
        if ab.get("damage"):
            parts.append((f"{dec(g(ab['damage']))} de dano" + ("/s" if zone else "")) if pt else
                         (f"{g(ab['damage'])} damage" + ("/s" if zone else "")))
        if ab.get("knock", 0) > 0:
            parts.append("empurra" if pt else "knocked back")
        if ab.get("knock", 0) < 0:
            parts.append("puxa para o centro" if pt else "pulled to the center")
        if ab.get("lift"):
            parts.append("lança para o alto" if pt else "launched upward")
        if ab.get("ignite"):
            parts.append("incendeia" if pt else "set on fire")
        if ab.get("effects"):
            parts.append(fx_text(ab["effects"], lang))
        if parts:
            out.append(("Inimigos: " if pt else "Enemies: ") + ", ".join(parts) + ".")

    def allies(zone):
        parts = []
        if ab.get("heal"):
            parts.append((f"cura {dec(g(ab['heal']))}" + ("/s" if zone else "")) if pt else
                         (f"heals {g(ab['heal'])}" + ("/s" if zone else "")))
        if ab.get("ally_effects"):
            parts.append(fx_text(ab["ally_effects"], lang))
        if parts:
            out.append(("Aliados e você: " if pt else "Allies and you: ") + ", ".join(parts) + ".")

    def selfpart():
        if ab.get("self_fx"):
            out.append(("Você: " if pt else "You: ") + fx_text(ab["self_fx"], lang) + ".")
        if ab.get("launch_up") or ab.get("launch_fwd"):
            out.append("Impulsiona você." if pt else "Launches you.")

    if t == "DASH":
        out.append((f"Disparada de {round(ab['speed'] * ab['ticks'])} blocos na direção em que você anda." if pt else
                    f"Dash of {round(ab['speed'] * ab['ticks'])} blocks toward your movement direction."))
    elif t == "LEAP":
        extra = (f" e {dec(g(ab['fwd']))} para a frente" if ab.get("fwd") else "") if pt else \
            (f" and {g(ab['fwd'])} forward" if ab.get("fwd") else "")
        out.append(("Impulso para o alto" + extra + ".") if pt else ("Upward launch" + extra + "."))
    elif t == "BLINK":
        out.append((f"Teleporta até onde você mira (até {g(ab['range'])} blocos)." if pt else
                    f"Teleports to where you aim (up to {g(ab['range'])} blocks)."))
    elif t in ("AREA", "ZONE"):
        zone = t == "ZONE"
        if ab["center"] == "self":
            where = "Ao redor de você" if pt else "Around you"
        elif ab.get("throw"):
            where = (f"Arremessada até {g(ab['range'])} blocos" if pt else f"Thrown up to {g(ab['range'])} blocks")
        else:
            where = (f"No ponto que você mira (até {g(ab['range'])} blocos)" if pt else
                     f"At the point you aim (up to {g(ab['range'])} blocks)")
        head = f"{where}, " + (f"raio {dec(g(ab['radius']))}" if pt else f"radius {g(ab['radius'])}")
        if zone:
            head += (f", dura {dec(g(ab['duration']))}s" if pt else f", lasts {g(ab['duration'])}s")
            if ab.get("follow"):
                head += ", segue você" if pt else ", follows you"
        out.append(head + ".")
        foes(zone)
        allies(zone)
        selfpart()
        if ab.get("facing"):
            out.append("Só afeta quem está olhando para o ponto." if pt else "Only affects those looking at the point.")
    elif t == "WALL":
        pos = ("à sua frente" if pt else "in front of you") if ab.get("along") else \
            (f"no ponto que você mira (até {g(ab['range'])} blocos)" if pt else f"at your aim point (up to {g(ab['range'])} blocks)")
        out.append((f"Ergue uma parede de {ab['width']}x{ab['height']} blocos {pos} por {dec(g(ab['duration']))}s." if pt else
                    f"Raises a {ab['width']}x{ab['height']} block wall {pos} for {g(ab['duration'])}s."))
        foes(True)
    elif t == "BEAM":
        s = (f"Tiro instantâneo (até {g(ab['range'])} blocos): {dec(g(ab['damage']))} de dano" if pt else
             f"Instant shot (up to {g(ab['range'])} blocks): {g(ab['damage'])} damage")
        if ab.get("head", 1) > 1:
            s += (f", x{dec(g(ab['head']))} na cabeça" if pt else f", x{g(ab['head'])} on headshots")
        if ab.get("aoe"):
            s += (f"; explosão de raio {g(ab['aoe'])} ({g(ab['aoe_damage'])} de dano)" if pt else
                  f"; explosion radius {g(ab['aoe'])} ({g(ab['aoe_damage'])} damage)")
        out.append(s + ".")
        if ab.get("effects"):
            out.append(("No acerto: " if pt else "On hit: ") + fx_text(ab["effects"], lang) + ".")
    elif t == "MODE":
        s = (f"Por {dec(g(ab['duration']))}s, o clique esquerdo dispara ({ab['ammo']} tiro(s), alcance {g(ab['range'])})."
             if pt else f"For {g(ab['duration'])}s, left click fires ({ab['ammo']} shot(s), range {g(ab['range'])}).")
        out.append(s)
        if ab.get("body_hits"):
            out.append(f"{ab['body_hits']} acertos no corpo matam." if pt else f"{ab['body_hits']} body hits kill.")
        elif ab.get("body_dmg"):
            out.append(f"{dec(g(ab['body_dmg']))} de dano no corpo." if pt else f"{g(ab['body_dmg'])} body damage.")
        if ab.get("head_kill"):
            out.append("Cabeça = morte instantânea." if pt else "Headshot = instant kill.")
        if ab.get("aoe"):
            out.append((f"Explosão de raio {g(ab['aoe'])} ({g(ab['aoe_damage'])} de dano)." if pt else
                        f"Explosion radius {g(ab['aoe'])} ({g(ab['aoe_damage'])} damage)."))
        if ab.get("refill"):
            out.append("Abater devolve toda a munição." if pt else "A kill refills all ammo.")
        selfpart()
    if t in ("DASH", "LEAP", "BLINK") and ab.get("self_fx"):
        selfpart()
    if ab.get("vfx") == "smoke":
        out.append("Fumaça 3D: bloqueia a visão da área." if pt else "3D smoke: blocks sight across the area.")
    if ab.get("anchor"):
        out.append((f"1º uso: marca um ponto. 2º uso (até {g(ab['range'])} blocos, {g(ab['anchor_time'])}s): volta ao ponto." if pt else
                    f"1st use: plants an anchor. 2nd use (within {g(ab['range'])} blocks, {g(ab['anchor_time'])}s): teleports back."))
    if ab.get("gun"):
        out.append((f"Arma equipada: {ab['ammo']} balas, SEM recarga entre tiros; recarrega sozinha ao esvaziar ({g(ab['reload'] / 20)}s). Aperte de novo para guardar."
                    if pt else f"Equips a pistol: {ab['ammo']} rounds, NO cooldown between shots; auto-reloads when empty ({g(ab['reload'] / 20)}s). Press again to holster."))
    if ab.get("kill_radius"):
        out.append((f"Ao abater: campo de lentidão de raio {g(ab['kill_radius'])} no ponto da morte." if pt else
                    f"On kill: slowing field of radius {g(ab['kill_radius'])} at the kill."))
    if ab.get("needs_kill"):
        out.append("Só pode ser usada depois de abater um inimigo." if pt else "Usable only after getting a kill.")
    if ab.get("ndy"):
        out.append((f"Passiva: ao morrer, você fica {g(ab['ndy_time'])}s de pé como fantasma; se abater alguém nesse tempo, volta à vida; se não, morre."
                    if pt else f"Passive: when you would die you linger {g(ab['ndy_time'])}s as a ghost; get a kill in time to come back, or die."))
    return " ".join(out)



# ----------------------------------------------------------------------------------------------------------------------
# Contrato de Reatribuição (troca de agente)
# ----------------------------------------------------------------------------------------------------------------------
def emit_contract():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([5, 2, 26, 29], radius=2, fill=(236, 226, 200, 255), outline=(120, 96, 60, 255))
    d.rectangle([5, 2, 26, 6], fill=(214, 60, 74, 255))
    for i in range(5):
        d.line([(9, 11 + i * 3), (22 - (i % 2) * 4, 11 + i * 3)], fill=(90, 80, 70, 255))
    d.ellipse([17, 20, 27, 30], fill=(214, 60, 74, 255), outline=(255, 214, 120, 255))
    d.line([(20, 25), (24, 25)], fill=(255, 255, 255, 255))
    d.line([(22, 23), (22, 27)], fill=(255, 255, 255, 255))
    out = os.path.join(RES, "assets", MOD, "textures", "item", "reassignment_contract.png")
    img.save(out)
    write_json(os.path.join(RES, "assets", MOD, "models", "item", "reassignment_contract.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/reassignment_contract"}})
    write_json(os.path.join(RES, "assets", MOD, "items", "reassignment_contract.json"),
               {"model": {"type": "minecraft:model", "model": f"{MOD}:item/reassignment_contract"}})
    write_json(os.path.join(RES, "data", MOD, "recipe", "reassignment_contract.json"), {
        "type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": ["minecraft:paper", "minecraft:ender_pearl", "minecraft:gold_ingot", "minecraft:redstone"],
        "result": {"id": f"{MOD}:reassignment_contract", "count": 1}})

def emit_lang():
    pt = {
        "itemGroup.agentsmod.main": "Agents Mod",
        "key.category.agentsmod.main": "Agents Mod",
        "key.agentsmod.c": "Habilidade C", "key.agentsmod.q": "Habilidade Q",
        "key.agentsmod.e": "Habilidade E", "key.agentsmod.x": "Ultimate (X)",
        "key.agentsmod.loadout": "Abrir loadout de agentes",
        "message.agentsmod.unlocked": "Habilidade desbloqueada: %s",
        "message.agentsmod.already_unlocked": "Você já desbloqueou %s",
        "message.agentsmod.equipped": "Agente equipado: %s",
        "hud.agentsmod.ready": "PRONTO", "hud.agentsmod.locked": "BLOQ.",
        "hud.agentsmod.ammo": "Munição: %s",
        "hud.agentsmod.no_agent": "Sem agente (V)",
        "gui.agentsmod.loadout.title": "LOADOUT DE AGENTES",
        "gui.agentsmod.equip": "Equipar", "gui.agentsmod.equipped": "EQUIPADO",
        "gui.agentsmod.close": "Fechar",
        "gui.agentsmod.unlocked": "DESBLOQUEADA", "gui.agentsmod.locked": "BLOQUEADA",
        "gui.agentsmod.recipe": "Receita (sem forma):", "gui.agentsmod.cooldown": "Recarga: %ss",
        "gui.agentsmod.ultimate": "ULTIMATE", "gui.agentsmod.hint": "Passe o mouse nas habilidades para ver os detalhes",
        "gui.agentsmod.owned": "%s/4 habilidades",
        "item.agentsmod.reassignment_contract": "Contrato de Reatribuição",
        "message.agentsmod.contract_used": "Contrato usado: agente retirado. Escolha o novo agente.",
        "message.agentsmod.contract_none": "Você não tem agente ativo. Use um item de desbloqueio ou abra o loadout (V).",
        "message.agentsmod.wrong_agent": "Esta habilidade é de %s. Ative esse agente primeiro (Contrato de Reatribuição).",
        "message.agentsmod.need_contract": "Para trocar de agente, use um Contrato de Reatribuição.",
        "gui.agentsmod.swap_ready": "TROCA LIBERADA",
        "gui.agentsmod.need_contract": "Requer Contrato de Reatribuição",
        "hud.agentsmod.knives": "FACAS",
        "message.agentsmod.passive": "Esta habilidade é passiva: ativa sozinha.",
        "message.agentsmod.need_kill": "Precisa de um abate para usar.",
        "message.agentsmod.revived": "De volta à vida!",
        "message.agentsmod.ndy": "NOT DEAD YET: abata alguém em %s s para voltar à vida!",
        "message.agentsmod.anchor_set": "Âncora marcada. Aperte de novo para voltar até ela.",
        "message.agentsmod.anchor_far": "Âncora fora de alcance.",
    }
    en = {
        "itemGroup.agentsmod.main": "Agents Mod",
        "key.category.agentsmod.main": "Agents Mod",
        "key.agentsmod.c": "Ability C", "key.agentsmod.q": "Ability Q",
        "key.agentsmod.e": "Ability E", "key.agentsmod.x": "Ultimate (X)",
        "key.agentsmod.loadout": "Open agent loadout",
        "message.agentsmod.unlocked": "Ability unlocked: %s",
        "message.agentsmod.already_unlocked": "You already unlocked %s",
        "message.agentsmod.equipped": "Agent equipped: %s",
        "hud.agentsmod.ready": "READY", "hud.agentsmod.locked": "LOCKED",
        "hud.agentsmod.ammo": "Ammo: %s",
        "hud.agentsmod.no_agent": "No agent (V)",
        "gui.agentsmod.loadout.title": "AGENT LOADOUT",
        "gui.agentsmod.equip": "Equip", "gui.agentsmod.equipped": "EQUIPPED",
        "gui.agentsmod.close": "Close",
        "gui.agentsmod.unlocked": "UNLOCKED", "gui.agentsmod.locked": "LOCKED",
        "gui.agentsmod.recipe": "Recipe (shapeless):", "gui.agentsmod.cooldown": "Cooldown: %ss",
        "gui.agentsmod.ultimate": "ULTIMATE", "gui.agentsmod.hint": "Hover an ability to see its details",
        "gui.agentsmod.owned": "%s/4 abilities",
        "item.agentsmod.reassignment_contract": "Reassignment Contract",
        "message.agentsmod.contract_used": "Contract used: agent retired. Pick a new agent.",
        "message.agentsmod.contract_none": "You have no active agent. Use an unlock item or open the loadout (V).",
        "message.agentsmod.wrong_agent": "This ability belongs to %s. Activate that agent first (Reassignment Contract).",
        "message.agentsmod.need_contract": "Use a Reassignment Contract to switch agents.",
        "gui.agentsmod.swap_ready": "SWAP READY",
        "gui.agentsmod.need_contract": "Requires Reassignment Contract",
        "hud.agentsmod.knives": "KNIVES",
        "message.agentsmod.passive": "This ability is passive: it triggers on its own.",
        "message.agentsmod.need_kill": "You need a kill to use this.",
        "message.agentsmod.revived": "Back from the dead!",
        "message.agentsmod.ndy": "NOT DEAD YET: get a kill within %s s to come back!",
        "message.agentsmod.anchor_set": "Anchor placed. Press again to return to it.",
        "message.agentsmod.anchor_far": "Anchor out of range.",
    }
    for r, (rp, re_) in ROLES.items():
        pt[f"role.agentsmod.{r}"] = rp
        en[f"role.agentsmod.{r}"] = re_
    for agent in AGENTS:
        pt[f'agent.agentsmod.{agent["id"]}'] = agent["name"]
        en[f'agent.agentsmod.{agent["id"]}'] = agent["name"]
        for ab in agent["abilities"]:
            pt[f'ability.agentsmod.{ab["id"]}'] = ab["name"]
            en[f'ability.agentsmod.{ab["id"]}'] = ab["name"]
            pt[f'ability.agentsmod.{ab["id"]}.desc'] = describe(ab, "pt")
            en[f'ability.agentsmod.{ab["id"]}.desc'] = describe(ab, "en")
            pt[f'item.agentsmod.{ab["id"]}'] = f'Desbloqueio: {agent["name"]} - {ab["name"]}'
            en[f'item.agentsmod.{ab["id"]}'] = f'Unlock: {agent["name"]} - {ab["name"]}'
    write_json(os.path.join(RES, "assets", MOD, "lang", "pt_br.json"), pt)
    write_json(os.path.join(RES, "assets", MOD, "lang", "en_us.json"), en)


def main():
    # limpa o que é gerado (nunca toca em arquivos escritos à mão fora destas pastas)
    for sub in (("assets", MOD), ("data", MOD), ("agentsmod",)):
        shutil.rmtree(os.path.join(RES, *sub), ignore_errors=True)
    os.makedirs(os.path.join(RES, "assets", MOD, "textures", "item"), exist_ok=True)
    normalize()
    emit_agents_json()
    emit_fx()
    emit_icons()
    emit_items_and_recipes()
    jett_models.emit(RES, AGENTS)
    emit_contract()
    emit_lang()
    n = sum(len(a["abilities"]) for a in AGENTS)
    print(f"OK: {len(AGENTS)} agentes, {n} habilidades")


if __name__ == "__main__":
    main()
