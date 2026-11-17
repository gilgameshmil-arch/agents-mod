package dev.openai.agentsmod;

import dev.openai.agentsmod.Defs.AbilityDef;
import dev.openai.agentsmod.Defs.AgentDef;

import java.util.Arrays;
import java.util.List;

/** Estado das habilidades no cliente, alimentado pelo servidor (StateS2C). Usado pelo HUD, pelas teclas e pelo loadout. */
public final class ClientState {
    private ClientState() {}

    private static int selected = -1;
    private static int[] words = new int[Defs.words()];
    private static final long[] COOLDOWN_END = new long[4];
    private static long clientTicks = 0;
    private static int ammo = 0;

    private static final long[] PRESSED_AT = {-100, -100, -100, -100};
    private static final long[] FLASH_AT = {-100, -100, -100, -100};
    private static final int[] LAST_REMAINING = new int[4];

    public static long ticks() { return clientTicks; }

    public static long pressedAt(int slot) { return PRESSED_AT[slot]; }

    public static long flashAt(int slot) { return FLASH_AT[slot]; }

    public static void tick() {
        clientTicks++;
        AgentDef a = agent();
        for (int i = 0; i < 4; i++) {
            int rem = a != null && isUnlocked(a.abilities[i]) ? remainingTicks(i) : 0;
            if (LAST_REMAINING[i] > 0 && rem == 0) FLASH_AT[i] = clientTicks; // recarga acabou: brilho
            LAST_REMAINING[i] = rem;
        }
    }

    public static void apply(Net.StateS2C s) {
        selected = s.selected();
        words = new int[Math.max(Defs.words(), s.unlocked().size())];
        List<Integer> u = s.unlocked();
        for (int i = 0; i < u.size(); i++) words[i] = u.get(i);
        for (int i = 0; i < 4; i++) COOLDOWN_END[i] = clientTicks + (i < s.cooldowns().size() ? s.cooldowns().get(i) : 0);
        ammo = s.ammo();
    }

    public static void reset() {
        selected = -1;
        Arrays.fill(words, 0);
        Arrays.fill(COOLDOWN_END, 0);
        ammo = 0;
    }

    public static int ammo() { return ammo; }

    public static AgentDef agent() { return Defs.agent(selected); }

    public static boolean isUnlocked(AbilityDef d) {
        int w = d.index >> 5;
        return w < words.length && (words[w] & (1 << (d.index & 31))) != 0;
    }

    public static int unlockedCount(AgentDef agent) {
        int n = 0;
        for (AbilityDef d : agent.abilities) if (isUnlocked(d)) n++;
        return n;
    }

    public static boolean anyUnlocked() {
        for (int w : words) if (w != 0) return true;
        return false;
    }

    public static int remainingTicks(int slot) {
        return (int) Math.max(0, COOLDOWN_END[slot] - clientTicks);
    }

    public static boolean isReady(AbilityDef d) {
        return isUnlocked(d) && remainingTicks(d.slot) == 0;
    }

    /** Recarga "prevista" no cliente no instante da tecla; o servidor corrige no próximo StateS2C. */
    public static void startCooldown(AbilityDef d) {
        COOLDOWN_END[d.slot] = clientTicks + d.cooldownTicks;
        PRESSED_AT[d.slot] = clientTicks;
    }
}
