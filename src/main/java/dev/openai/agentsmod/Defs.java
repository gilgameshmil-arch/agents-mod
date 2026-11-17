package dev.openai.agentsmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tabela de agentes e habilidades, lida de agentsmod/agents.json (gerado por tools/generate_assets.py).
 * Servidor e cliente leem o mesmo arquivo, então os índices são idênticos dos dois lados.
 * Índice global de uma habilidade = índiceDoAgente * 4 + slot (0=C, 1=Q, 2=E, 3=X).
 */
public final class Defs {
    private Defs() {}

    public static final String[] KEYS = {"C", "Q", "E", "X"};

    public enum Type { DASH, LEAP, BLINK, AREA, ZONE, WALL, BEAM, MODE }

    public record EffectSpec(String id, int ticks, int amplifier) {}

    public record RecipeEntry(String item, int count) {}

    public static final class AgentDef {
        public final String id;
        public final int index;
        public final String role;
        public final int color;
        public final AbilityDef[] abilities = new AbilityDef[4];

        AgentDef(String id, int index, String role, int color) {
            this.id = id;
            this.index = index;
            this.role = role;
            this.color = color & 0xFFFFFF;
        }

        public String nameKey() { return "agent." + AgentsMod.MOD_ID + "." + id; }

        public String roleKey() { return "role." + AgentsMod.MOD_ID + "." + role; }
    }

    public static final class AbilityDef {
        public final String id;
        public final AgentDef agent;
        public final int slot;
        public final int index;
        public final Type type;
        public final int cooldownTicks;
        public final JsonObject p;
        public final List<RecipeEntry> recipe = new ArrayList<>();

        AbilityDef(String id, AgentDef agent, int slot, int index, Type type, int cooldownTicks, JsonObject p) {
            this.id = id;
            this.agent = agent;
            this.slot = slot;
            this.index = index;
            this.type = type;
            this.cooldownTicks = cooldownTicks;
            this.p = p;
        }

        public String nameKey() { return "ability." + AgentsMod.MOD_ID + "." + id; }

        public String descKey() { return "ability." + AgentsMod.MOD_ID + "." + id + ".desc"; }

        public boolean isUltimate() { return slot == 3; }

        public boolean has(String key) { return p.has(key) && !p.get(key).isJsonNull(); }

        public double d(String key, double def) { return has(key) ? p.get(key).getAsDouble() : def; }

        public int i(String key, int def) { return has(key) ? p.get(key).getAsInt() : def; }

        public boolean b(String key, boolean def) { return has(key) ? p.get(key).getAsBoolean() : def; }

        public String s(String key, String def) { return has(key) ? p.get(key).getAsString() : def; }

        /** Lista de efeitos [[id, segundos, amplificador], ...]. */
        public List<EffectSpec> effects(String key) {
            List<EffectSpec> list = new ArrayList<>();
            if (!has(key)) return list;
            for (JsonElement e : p.getAsJsonArray(key)) {
                JsonArray a = e.getAsJsonArray();
                list.add(new EffectSpec(a.get(0).getAsString(), (int) Math.round(a.get(1).getAsDouble() * 20),
                        a.get(2).getAsInt()));
            }
            return list;
        }
    }

    public static final List<AgentDef> AGENTS = new ArrayList<>();
    public static final List<AbilityDef> ABILITIES = new ArrayList<>();
    private static final Map<String, AgentDef> AGENT_BY_ID = new HashMap<>();
    private static final Map<String, AbilityDef> ABILITY_BY_ID = new HashMap<>();

    static {
        try (InputStream in = Defs.class.getResourceAsStream("/agentsmod/agents.json")) {
            if (in == null) throw new IllegalStateException("agentsmod/agents.json não encontrado no jar");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement ae : root.getAsJsonArray("agents")) {
                JsonObject a = ae.getAsJsonObject();
                AgentDef agent = new AgentDef(a.get("id").getAsString(), AGENTS.size(), a.get("role").getAsString(),
                        a.get("color").getAsInt());
                for (JsonElement be : a.getAsJsonArray("abilities")) {
                    JsonObject b = be.getAsJsonObject();
                    int slot = b.get("slot").getAsInt();
                    AbilityDef def = new AbilityDef(b.get("id").getAsString(), agent, slot, agent.index * 4 + slot,
                            Type.valueOf(b.get("type").getAsString()), b.get("cd_ticks").getAsInt(), b);
                    for (JsonElement re : b.getAsJsonArray("recipe")) {
                        JsonArray r = re.getAsJsonArray();
                        def.recipe.add(new RecipeEntry(r.get(0).getAsString(), r.get(1).getAsInt()));
                    }
                    agent.abilities[slot] = def;
                    ABILITIES.add(def);
                    ABILITY_BY_ID.put(def.id, def);
                }
                AGENTS.add(agent);
                AGENT_BY_ID.put(agent.id, agent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao ler agents.json", e);
        }
    }

    public static AgentDef agent(String id) { return AGENT_BY_ID.get(id); }

    public static AgentDef agent(int index) { return index >= 0 && index < AGENTS.size() ? AGENTS.get(index) : null; }

    public static AbilityDef ability(String id) { return ABILITY_BY_ID.get(id); }

    /** Quantidade de inteiros de 32 bits para guardar um bit por habilidade. */
    public static int words() { return (ABILITIES.size() + 31) / 32; }
}
