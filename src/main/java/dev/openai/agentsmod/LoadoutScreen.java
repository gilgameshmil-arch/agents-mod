package dev.openai.agentsmod;

import dev.openai.agentsmod.Defs.AbilityDef;
import dev.openai.agentsmod.Defs.AgentDef;
import dev.openai.agentsmod.Defs.RecipeEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Loadout de agentes (tecla V). Esquerda: grade com os agentes (rolável). Direita: detalhes do agente escolhido,
 * suas 4 habilidades (ícone, tecla, recarga, descrição, receita de crafting) e o botão de equipar.
 */
public class LoadoutScreen extends Screen {
    private static final int CARD_W = 74, CARD_H = 34, CARD_GAP = 4;
    private static final int PAD = 10;

    private int view = -1;
    private int scroll = 0;
    private int gridX, gridY, gridW, gridH, cols, detailX, detailW, panelX, panelY, panelW, panelH;
    private int equipX, equipY, equipW = 110, equipH = 18;

    public LoadoutScreen() {
        super(Component.translatable("gui.agentsmod.loadout.title"));
        AgentDef current = ClientState.agent();
        view = current != null ? current.index : 0;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, 520);
        panelH = Math.min(this.height - 20, 280);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        detailW = Math.max(210, panelW * 45 / 100);
        gridX = panelX + PAD;
        gridY = panelY + 30;
        gridW = panelW - detailW - PAD * 3;
        gridH = panelH - 30 - PAD;
        cols = Math.max(1, (gridW + CARD_GAP) / (CARD_W + CARD_GAP));
        detailX = panelX + panelW - detailW - PAD;
        equipX = detailX;
        equipY = panelY + panelH - PAD - equipH;
        clampScroll();
    }

    private int rows() { return (Defs.AGENTS.size() + cols - 1) / cols; }

    private int maxScroll() { return Math.max(0, rows() * (CARD_H + CARD_GAP) - CARD_GAP - gridH); }

    private void clampScroll() { scroll = Math.max(0, Math.min(maxScroll(), scroll)); }

    @Override
    public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------ entrada

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) (scrollY * 18);
        clampScroll();
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int hit = cardAt(mx, my);
        if (hit >= 0) {
            view = hit;
            if (doubleClick && canEquip(Defs.agent(hit))) ClientPlayNetworking.send(new Net.SelectC2S(hit));
            return true;
        }
        AgentDef viewed = Defs.agent(view);
        if (viewed != null && inside(mx, my, equipX, equipY, equipW, equipH) && canEquip(viewed)) {
            ClientPlayNetworking.send(new Net.SelectC2S(viewed.index));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Só dá para ativar um agente quando não há agente ativo (após o Contrato) ou se ele já é o ativo. */
    private static boolean canEquip(AgentDef a) {
        AgentDef cur = ClientState.agent();
        return a != null && (cur == null || cur.index == a.index);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int cardAt(double mx, double my) {
        if (!inside(mx, my, gridX, gridY, gridW, gridH)) return -1;
        int col = (int) ((mx - gridX) / (CARD_W + CARD_GAP));
        int row = (int) ((my - gridY + scroll) / (CARD_H + CARD_GAP));
        if (col >= cols) return -1;
        int cx = gridX + col * (CARD_W + CARD_GAP), cy = gridY + row * (CARD_H + CARD_GAP) - scroll;
        if (!inside(mx, my, cx, cy, CARD_W, CARD_H)) return -1;
        int idx = row * cols + col;
        return idx >= 0 && idx < Defs.AGENTS.size() ? idx : -1;
    }

    // ------------------------------------------------------------------ desenho

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xB0000000);
        g.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xFF2A2F3A);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF00C0F16);

        AgentDef viewed = Defs.agent(view);
        int accent = viewed != null ? 0xFF000000 | viewed.color : 0xFF7FE0FF;
        g.fill(panelX, panelY, panelX + panelW, panelY + 2, accent);
        g.drawString(this.font, Component.translatable("gui.agentsmod.loadout.title").withStyle(s -> s.withBold(true)),
                panelX + PAD, panelY + 10, 0xFFFFFFFF, false);
        Component hint = Component.translatable("gui.agentsmod.hint");
        g.drawString(this.font, hint, panelX + panelW - PAD - this.font.width(hint), panelY + 10, 0xFF7C8494, false);

        drawGrid(g, mouseX, mouseY);
        drawDetail(g, viewed, accent, mouseX, mouseY);
    }

    private void drawGrid(GuiGraphics g, int mouseX, int mouseY) {
        g.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        AgentDef equipped = ClientState.agent();
        for (AgentDef a : Defs.AGENTS) {
            int col = a.index % cols, row = a.index / cols;
            int x = gridX + col * (CARD_W + CARD_GAP);
            int y = gridY + row * (CARD_H + CARD_GAP) - scroll;
            if (y + CARD_H < gridY || y > gridY + gridH) continue;
            boolean hover = inside(mouseX, mouseY, x, y, CARD_W, CARD_H) && inside(mouseX, mouseY, gridX, gridY, gridW, gridH);
            boolean selected = a.index == view;
            int base = 0xFF000000 | a.color;
            g.fill(x, y, x + CARD_W, y + CARD_H, hover ? 0xFF1B2130 : 0xFF121723);
            g.fill(x, y, x + 3, y + CARD_H, base);
            if (selected) AgentsHud.frame(g, x, y, CARD_W, CARD_H, base);
            else if (hover) AgentsHud.frame(g, x, y, CARD_W, CARD_H, AgentsHud.shade(a.color, 0.55f));
            ItemStack icon = new ItemStack(AgentsMod.UNLOCK_ITEMS.get(a.abilities[3].index));
            g.renderItem(icon, x + CARD_W - 20, y + 4);
            g.drawString(this.font, Component.translatable(a.nameKey()), x + 7, y + 5, 0xFFFFFFFF, false);
            g.drawString(this.font, Component.translatable(a.roleKey()), x + 7, y + 15, AgentsHud.shade(a.color, 0.85f), false);
            int owned = ClientState.unlockedCount(a);
            for (int i = 0; i < 4; i++) { // 4 pips: habilidades desbloqueadas
                g.fill(x + 7 + i * 9, y + 26, x + 14 + i * 9, y + 29, i < owned ? base : 0xFF2B303B);
            }
            if (equipped != null && equipped.index == a.index) {
                g.fill(x + CARD_W - 9, y + CARD_H - 9, x + CARD_W - 4, y + CARD_H - 4, 0xFF7CFF8A);
            }
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int trackH = gridH;
            int barH = Math.max(14, trackH * gridH / (gridH + maxScroll()));
            int barY = gridY + (trackH - barH) * scroll / maxScroll();
            g.fill(gridX + gridW + 1, gridY, gridX + gridW + 3, gridY + trackH, 0xFF1B2030);
            g.fill(gridX + gridW + 1, barY, gridX + gridW + 3, barY + barH, 0xFF7C8494);
        }
    }

    private void drawDetail(GuiGraphics g, AgentDef a, int accent, int mouseX, int mouseY) {
        if (a == null) return;
        int x = detailX, y = panelY + 28;
        g.fill(x - 4, y - 4, x + detailW + 4, panelY + panelH - PAD + 2, 0xFF0F131C);
        g.drawString(this.font, Component.translatable(a.nameKey()).withStyle(s -> s.withBold(true)), x, y, 0xFFFFFFFF, false);
        Component owned = Component.translatable("gui.agentsmod.owned", ClientState.unlockedCount(a));
        g.drawString(this.font, owned, x + detailW - this.font.width(owned), y, 0xFF8A93A0, false);
        g.drawString(this.font, Component.translatable(a.roleKey()), x, y + 10, AgentsHud.shade(a.color, 0.9f), false);
        y += 24;

        int rowH = Math.max(36, (equipY - y - 4) / 4);
        for (int slot = 0; slot < 4; slot++) {
            AbilityDef d = a.abilities[slot];
            boolean unlocked = ClientState.isUnlocked(d);
            int ry = y + slot * rowH;
            g.fill(x, ry, x + detailW, ry + rowH - 2, 0xFF141A26);
            g.fill(x, ry, x + 2, ry + rowH - 2, unlocked ? accent : 0xFF4A4A52);

            g.fill(x + 5, ry + 4, x + 25, ry + 24, 0xFF05060A);
            g.renderItem(new ItemStack(AgentsMod.UNLOCK_ITEMS.get(d.index)), x + 7, ry + 6);
            if (!unlocked) g.fill(x + 5, ry + 4, x + 25, ry + 24, 0x90000000);

            Component name = Component.literal("[" + Defs.KEYS[slot] + "] ").append(Component.translatable(d.nameKey()));
            g.drawString(this.font, name, x + 30, ry + 4, unlocked ? 0xFFFFFFFF : 0xFF9A9AA2, false);
            Component state = Component.translatable(unlocked ? "gui.agentsmod.unlocked" : "gui.agentsmod.locked");
            g.drawString(this.font, state, x + detailW - 4 - this.font.width(state), ry + 4, unlocked ? 0xFF7CFF8A : 0xFFFF7C7C, false);
            String extra = String.format(Locale.ROOT, "%gs", d.cooldownTicks / 20.0);
            Component cd = Component.translatable("gui.agentsmod.cooldown", extra);
            Component sub = d.isUltimate() ? Component.translatable("gui.agentsmod.ultimate").append(" - ").append(cd) : cd;
            g.drawString(this.font, sub, x + 30, ry + 14, d.isUltimate() ? 0xFFFFC857 : 0xFF8A93A0, false);

            // receita: ingredientes (somente se ainda bloqueada)
            if (!unlocked) {
                int rx = x + 30;
                for (RecipeEntry re : d.recipe) {
                    Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("minecraft", re.item().replace("minecraft:", "")));
                    g.renderItem(new ItemStack(item), rx, ry + rowH - 20);
                    g.drawString(this.font, "x" + re.count(), rx + 11, ry + rowH - 10, 0xFFCCCCCC, true);
                    rx += 28;
                }
            }
            // descrição ao passar o mouse sobre a linha: painel flutuante
            if (inside(mouseX, mouseY, x, ry, detailW, rowH - 2)) {
                hoverDesc = d;
                hoverRowY = ry;
            }
        }
        drawHoverDescription(g, accent);

        AgentDef equipped = ClientState.agent();
        boolean isEquipped = equipped != null && equipped.index == a.index;
        boolean can = canEquip(a);
        boolean hoverEq = can && inside(mouseX, mouseY, equipX, equipY, equipW, equipH);
        int fill = isEquipped ? 0xFF1F5E2B : !can ? 0xFF262A33 : hoverEq ? AgentsHud.shade(a.color, 0.85f) : AgentsHud.shade(a.color, 0.6f);
        g.fill(equipX, equipY, equipX + equipW, equipY + equipH, fill);
        Component eq = Component.translatable(isEquipped ? "gui.agentsmod.equipped" : "gui.agentsmod.equip");
        g.drawString(this.font, eq, equipX + (equipW - this.font.width(eq)) / 2, equipY + 5, can ? 0xFFFFFFFF : 0xFF6E7480, false);
        if (!can) {
            Component need = Component.translatable("gui.agentsmod.need_contract");
            g.drawString(this.font, need, equipX + equipW + 8, equipY + 5, 0xFFD9B070, false);
        } else if (equipped == null) {
            Component ready = Component.translatable("gui.agentsmod.swap_ready");
            g.drawString(this.font, ready, equipX + equipW + 8, equipY + 5, 0xFF7CFF8A, false);
        }
    }

    private AbilityDef hoverDesc;
    private int hoverRowY;

    private void drawHoverDescription(GuiGraphics g, int accent) {
        AbilityDef d = hoverDesc;
        hoverDesc = null;
        if (d == null) return;
        int w = Math.min(230, detailX - panelX - 20);
        if (w < 120) w = 180;
        List<FormattedCharSequence> lines = this.font.split(Component.translatable(d.descKey()), w - 10);
        int h = 8 + lines.size() * 10;
        int x = Math.max(panelX + 4, detailX - w - 10);
        int y = Math.min(hoverRowY, panelY + panelH - h - 6);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, accent);
        g.fill(x, y, x + w, y + h, 0xF2080A10);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(this.font, lines.get(i), x + 5, y + 4 + i * 10, 0xFFE6E8EE, false);
        }
    }
}
