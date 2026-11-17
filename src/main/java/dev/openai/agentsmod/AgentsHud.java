package dev.openai.agentsmod;

import dev.openai.agentsmod.Defs.AbilityDef;
import dev.openai.agentsmod.Defs.AgentDef;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * HUD de agente (lado direito, vertical): cartão do agente com faixa angulada, 4 habilidades com ícone de 32px,
 * tecla em destaque, recarga animada (cortina + barra + segundos), brilho ao ficar pronta, pulso ao usar,
 * realce da tecla segurada e barra de munição do modo de tiro (facas da Blade Storm).
 */
public final class AgentsHud implements HudElement {
    private static final int W = 112;
    private static final int TILE_H = 38;
    private static final int GAP = 4;
    private static final int HEAD_H = 28;

    static int shade(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 255) * f), g = (int) (((rgb >> 8) & 255) * f), b = (int) ((rgb & 255) * f);
        return 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    static int alpha(int argb, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (argb & 0xFFFFFF); }

    static void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** Faixa com borda esquerda cortada em diagonal (estilo competitivo). */
    private static void slanted(GuiGraphics g, int x, int y, int w, int h, int color, int cut) {
        for (int i = 0; i < h; i++) {
            int off = Math.max(0, cut - i * cut / Math.max(1, h - 1));
            g.fill(x + off, y + i, x + w, y + i + 1, color);
        }
    }

    private static Identifier icon(AbilityDef d) {
        return Identifier.fromNamespaceAndPath(AgentsMod.MOD_ID, "textures/gui/ability/" + d.id + ".png");
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        AgentDef agent = ClientState.agent();
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        if (agent == null) {
            if (ClientState.anyUnlocked()) {
                Component t = Component.translatable("hud.agentsmod.no_agent");
                g.drawString(mc.font, t, sw - mc.font.width(t) - 8, sh / 2, 0xFFAAAAAA, true);
            }
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(false);
        double now = ClientState.ticks() + partial;
        int total = HEAD_H + GAP + 4 * (TILE_H + GAP) + (ClientState.ammo() > 0 ? 22 : 0);
        int x = sw - W - 6;
        int y = Math.max(4, sh / 2 - total / 2);
        int accent = 0xFF000000 | agent.color;

        // ---- cartão do agente
        slanted(g, x, y, W, HEAD_H, 0xD00B0E15, 10);
        slanted(g, x, y, 5, HEAD_H, accent, 10);
        g.fill(x + 10, y + HEAD_H - 2, x + W, y + HEAD_H - 1, alpha(accent, 140));
        g.drawString(mc.font, Component.translatable(agent.nameKey()).withStyle(s -> s.withBold(true)), x + 14, y + 4, 0xFFFFFFFF, false);
        g.drawString(mc.font, Component.translatable(agent.roleKey()), x + 14, y + 16, shade(agent.color, 0.95f), false);
        int owned = ClientState.unlockedCount(agent);
        for (int i = 0; i < 4; i++) {
            g.fill(x + W - 44 + i * 10, y + 8, x + W - 37 + i * 10, y + 12, i < owned ? accent : 0xFF2B303B);
        }
        y += HEAD_H + GAP;

        // ---- habilidades
        for (int slot = 0; slot < 4; slot++) {
            AbilityDef d = agent.abilities[slot];
            boolean unlocked = ClientState.isUnlocked(d);
            double remaining = unlocked ? Math.max(0, ClientState.remainingTicks(slot) - partial) : 0;
            boolean ready = unlocked && remaining <= 0;
            boolean held = AgentsClient.keyFor(slot).isDown() && unlocked;
            double press = now - ClientState.pressedAt(slot);
            double flash = now - ClientState.flashAt(slot);

            // fundo e realce de seleção (tecla segurada)
            slanted(g, x, y, W, TILE_H, held ? 0xE0202838 : 0xC00B0E15, 6);
            int edge = !unlocked ? 0xFF3A3D46 : ready ? accent : 0xFF77808E;
            g.fill(x + 6, y + TILE_H - 1, x + W, y + TILE_H, alpha(edge, 200));
            g.fill(x, y, x + 3, y + TILE_H, edge);

            // ícone 32x32 (arte própria de cada habilidade)
            int ix = x + 8, iy = y + 3;
            g.blit(RenderPipelines.GUI_TEXTURED, icon(d), ix, iy, 0f, 0f, 32, 32, 32, 32);
            if (!unlocked) g.fill(ix, iy, ix + 32, iy + 32, 0xB0000000);
            if (remaining > 0) { // cortina de recarga (desce) + segundos
                float f = (float) Math.min(1.0, remaining / d.cooldownTicks);
                g.fill(ix, iy, ix + 32, iy + Math.round(32 * f), 0xB8000000);
                String sec = String.format(Locale.ROOT, remaining >= 200 ? "%.0f" : "%.1f", remaining / 20.0);
                g.drawString(mc.font, sec, ix + 16 - mc.font.width(sec) / 2, iy + 12, 0xFFFFFFFF, true);
            }
            frame(g, ix - 1, iy - 1, 34, 34, ready ? accent : alpha(edge, 220));
            if (press >= 0 && press < 7) { // pulso: contorno que se expande e some
                int grow = (int) (press * 0.9);
                frame(g, ix - 1 - grow, iy - 1 - grow, 34 + grow * 2, 34 + grow * 2, alpha(accent, (int) (230 - press * 32)));
            }
            if (flash >= 0 && flash < 10) { // recarga acabou: brilho branco
                g.fill(ix, iy, ix + 32, iy + 32, alpha(0xFFFFFFFF, (int) (170 - flash * 17)));
            }

            // tecla em destaque + nome + estado
            String key = AgentsClient.keyFor(slot).getTranslatedKeyMessage().getString();
            int kw = Math.max(12, mc.font.width(key) + 6);
            g.fill(x + 44, y + 4, x + 44 + kw, y + 16, ready ? accent : 0xFF2B303B);
            g.drawString(mc.font, key, x + 44 + (kw - mc.font.width(key)) / 2, y + 6, ready ? 0xFF05060A : 0xFFC9CED8, false);
            if (d.isUltimate()) g.drawString(mc.font, "ULT", x + 48 + kw, y + 6, 0xFFFFC857, false);
            Component name = Component.translatable(d.nameKey());
            g.drawString(mc.font, name, x + 44, y + 19, unlocked ? 0xFFFFFFFF : 0xFF7A7A82, false);
            Component state = !unlocked ? Component.translatable("hud.agentsmod.locked")
                    : ready ? Component.translatable("hud.agentsmod.ready") : Component.empty();
            g.drawString(mc.font, state, x + 44, y + 28, ready ? accent : 0xFF8A93A0, false);

            // barra fina de recarga (progresso)
            if (remaining > 0) {
                float done = 1f - (float) Math.min(1.0, remaining / d.cooldownTicks);
                g.fill(x + 44, y + TILE_H - 5, x + W - 6, y + TILE_H - 3, 0xFF1B2030);
                g.fill(x + 44, y + TILE_H - 5, x + 44 + Math.round((W - 50) * done), y + TILE_H - 3, accent);
            }
            y += TILE_H + GAP;
        }

        // ---- munição do modo de tiro (ex.: facas da Blade Storm)
        if (ClientState.ammo() > 0) {
            AbilityDef ult = agent.abilities[3];
            int max = Math.max(1, ult.i("ammo", 5));
            slanted(g, x, y, W, 18, 0xC00B0E15, 6);
            g.drawString(mc.font, Component.translatable("hud.agentsmod.knives"), x + 10, y + 5, 0xFFC9CED8, false);
            int px = x + W - 8 - max * 9;
            for (int i = 0; i < max; i++) {
                boolean on = i < ClientState.ammo();
                g.fill(px + i * 9, y + 4, px + i * 9 + 6, y + 14, on ? accent : 0xFF2B303B);
                if (on) g.fill(px + i * 9, y + 4, px + i * 9 + 6, y + 6, 0xFFFFFFFF);
            }
        }
    }
}
