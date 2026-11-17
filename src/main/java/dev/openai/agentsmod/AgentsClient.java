package dev.openai.agentsmod;

import com.mojang.blaze3d.platform.InputConstants;
import dev.openai.agentsmod.Defs.AbilityDef;
import dev.openai.agentsmod.Defs.AgentDef;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

public class AgentsClient implements ClientModInitializer {
    private static final KeyMapping[] KEYS = new KeyMapping[4];
    private static KeyMapping loadoutKey;

    // Previsão local do dash: o cliente começa a se mover no mesmo tick da tecla; o servidor continua autoritativo.
    private static int dashTicksLeft = 0;
    private static double dashDx = 0, dashDz = 0, dashSpeed = 0, dashUp = 0;

    public static KeyMapping keyFor(int slot) { return KEYS[slot]; }

    public static KeyMapping loadoutKey() { return loadoutKey; }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(AgentsMod.MOD_ID, "main"));
        int[] codes = {InputConstants.KEY_C, InputConstants.KEY_Q, InputConstants.KEY_E, InputConstants.KEY_X};
        for (int i = 0; i < 4; i++) {
            KEYS[i] = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                    "key." + AgentsMod.MOD_ID + "." + Defs.KEYS[i].toLowerCase(), InputConstants.Type.KEYSYM, codes[i], category));
        }
        loadoutKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key." + AgentsMod.MOD_ID + ".loadout", InputConstants.Type.KEYSYM, InputConstants.KEY_V, category));

        ClientPlayNetworking.registerGlobalReceiver(Net.StateS2C.TYPE, (payload, context) -> ClientState.apply(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientState.reset();
            dashTicksLeft = 0;
        });

        // START_CLIENT_TICK roda antes do tratamento de teclas do jogo: quando uma habilidade desbloqueada usa a
        // tecla de uma ação vanilla (E = inventário, Q = soltar), consumimos o clique vanilla e só a habilidade dispara.
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            if (!client.isPaused()) ClientState.tick();
            applyDashPrediction(client);
            if (client.screen != null) return;

            while (loadoutKey.consumeClick()) client.setScreen(new LoadoutScreen());

            AgentDef agent = ClientState.agent();
            if (agent == null) return;
            if (ClientState.ammo() > 0) { // modo de tiro ativo: clique esquerdo dispara
                while (client.options.keyAttack.consumeClick()) {
                    ClientPlayNetworking.send(new Net.ShotC2S());
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
            }
            for (int slot = 0; slot < 4; slot++) {
                KeyMapping key = KEYS[slot];
                while (key.consumeClick()) {
                    AbilityDef def = agent.abilities[slot];
                    if (!ClientState.isUnlocked(def)) continue; // sem desbloqueio: a tecla segue vanilla
                    activate(client, def);
                    swallowVanillaConflicts(client, key);
                }
            }
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(AgentsMod.MOD_ID, "abilities"), new AgentsHud());
    }

    private static void activate(Minecraft client, AbilityDef def) {
        double[] dir = moveDirection(client);
        ClientPlayNetworking.send(new Net.AbilityC2S(def.slot, (float) dir[0], (float) dir[1]));
        if (!ClientState.isReady(def) || client.player.isSpectator()) return;
        if (def.type == Defs.Type.DASH) {
            dashDx = dir[0];
            dashDz = dir[1];
            dashSpeed = def.d("speed", 2.4);
            dashUp = def.d("up", 0);
            dashTicksLeft = def.i("ticks", 5);
            applyDashPrediction(client);
        } else if (def.type == Defs.Type.LEAP) {
            Vec3 v = client.player.getDeltaMovement();
            double yaw = Math.toRadians(client.player.getYRot());
            double fwd = def.d("fwd", 0);
            client.player.setDeltaMovement(v.x - Math.sin(yaw) * fwd, def.d("up", 1.0), v.z + Math.cos(yaw) * fwd);
        }
        ClientState.startCooldown(def);
    }

    private static void applyDashPrediction(Minecraft client) {
        if (dashTicksLeft <= 0 || client.player == null) return;
        double speed = dashTicksLeft > 1 ? dashSpeed : 0.35;
        client.player.setDeltaMovement(dashDx * speed, dashUp, dashDz * speed);
        dashTicksLeft--;
    }

    /** Direção horizontal desejada: teclas de movimento relativas ao olhar; sem teclas, para a frente. */
    private static double[] moveDirection(Minecraft client) {
        double yaw = Math.toRadians(client.player.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double lx = Math.cos(yaw), lz = Math.sin(yaw);
        int forward = (client.options.keyUp.isDown() ? 1 : 0) - (client.options.keyDown.isDown() ? 1 : 0);
        int strafe = (client.options.keyLeft.isDown() ? 1 : 0) - (client.options.keyRight.isDown() ? 1 : 0);
        if (forward == 0 && strafe == 0) forward = 1;
        double dx = fx * forward + lx * strafe;
        double dz = fz * forward + lz * strafe;
        double len = Math.sqrt(dx * dx + dz * dz);
        return new double[] {dx / len, dz / len};
    }

    private static void swallowVanillaConflicts(Minecraft client, KeyMapping ours) {
        InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(ours);
        for (KeyMapping other : client.options.keyMappings) {
            if (other == ours || isOurs(other)) continue;
            if (bound.equals(KeyBindingHelper.getBoundKeyOf(other))) {
                while (other.consumeClick()) {
                    // descarta o clique da ação vanilla que divide a tecla
                }
            }
        }
    }

    private static boolean isOurs(KeyMapping candidate) {
        if (candidate == loadoutKey) return true;
        for (KeyMapping key : KEYS) if (key == candidate) return true;
        return false;
    }
}
