package dev.openai.agentsmod;

import dev.openai.agentsmod.Defs.AbilityDef;
import dev.openai.agentsmod.Defs.AgentDef;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/** HUD vertical no lado DIREITO: cabeçalho do agente e as 4 habilidades (C, Q, E, X) empilhadas, com ícone e recarga. */
public final class AgentsHud implements HudElement {
    private static final int W = 92;
    private static final int H = 26;
    private static final int GAP = 3;
    private static final int HEAD = 20;

    static int shade(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 255) * f), g = (int) (((rgb >> 8) & 255) * f), b = (int) ((rgb & 255) * f);
        return 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    static void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        AgentDef agent = ClientState.agent();
        if (agent == null) {
            if (ClientState.anyUnlocked()) {
                g.drawString(mc.font, Component.translatable("hud.agentsmod.no_agent"),
                        mc.getWindow().getGuiScaledWidth() - 70, mc.getWindow().getGuiScaledHeight() / 2, 0xFFAAAAAA, true);
            }
            return;
        }
        int total = HEAD + 4 * (H + GAP);
        int x = mc.getWindow().getGuiScaledWidth() - W - 6;
        int y = mc.getWindow().getGuiScaledHeight() / 2 - total / 2;
        int accent = 0xFF000000 | agent.color;

        // cabeçalho
        g.fill(x, y, x + W, y + HEAD - 3, 0xC0101018);
        g.fill(x, y, x + 3, y + HEAD - 3, accent);
        g.drawString(mc.font, Component.translatable(agent.nameKey()).withStyle(s -> s.withBold(true)), x + 7, y + 2, 0xFFFFFFFF, false);
        g.drawString(mc.font, Component.translatable(agent.roleKey()), x + 7, y + 10, shade(agent.color, 0.9f), false);
        y += HEAD;

        for (int slot = 0; slot < 4; slot++) {
            AbilityDef d = agent.abilities[slot];
            boolean unlocked = ClientState.isUnlocked(d);
            int remaining = unlocked ? ClientState.remainingTicks(slot) : 0;
            boolean ready = unlocked && remaining == 0;
            int border = !unlocked ? 0xFF4A4A52 : ready ? accent : 0xFF8A93A0;

            g.fill(x, y, x + W, y + H, 0xC0101018);
            g.fill(x, y, x + W, y + 2, ready ? accent : 0x00000000);
            frame(g, x, y, W, H, border);

            // ícone 3D/2D do item da habilidade
            int ix = x + 5, iy = y + (H - 16) / 2;
            g.fill(ix - 1, iy - 1, ix + 17, iy + 17, 0xFF05060A);
            g.renderItem(new ItemStack(AgentsMod.UNLOCK_ITEMS.get(d.index)), ix, iy);
            if (!unlocked) g.fill(ix - 1, iy - 1, ix + 17, iy + 17, 0xA0000000);
            if (remaining > 0) {
                float f = Math.min(1f, remaining / (float) d.cooldownTicks);
                int hh = Math.round(18 * f);
                g.fill(ix - 1, iy - 1, ix + 17, iy - 1 + hh, 0xB0000000);
            }

            int textColor = unlocked ? 0xFFFFFFFF : 0xFF7A7A82;
            g.drawString(mc.font, Component.translatable(d.nameKey()), x + 26, y + 4, textColor, false);
            Component status;
            if (!unlocked) status = Component.translatable("hud.agentsmod.locked");
            else if (remaining > 0) status = Component.literal(String.format(Locale.ROOT, "%.1fs", remaining / 20f));
            else status = Component.translatable("hud.agentsmod.ready");
            Component line = Component.literal("[").append(AgentsClient.keyFor(slot).getTranslatedKeyMessage())
                    .append("] ").append(status);
            g.drawString(mc.font, line, x + 26, y + 14, ready ? accent : 0xFF9AA0AA, false);

            if (d.isUltimate() && ClientState.ammo() > 0) {
                g.drawString(mc.font, Component.translatable("hud.agentsmod.ammo", ClientState.ammo()),
                        x - mc.font.width(Component.translatable("hud.agentsmod.ammo", ClientState.ammo())) - 4, y + 9, accent, true);
            }
            y += H + GAP;
        }
    }
}
