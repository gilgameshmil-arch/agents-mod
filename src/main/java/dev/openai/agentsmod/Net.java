package dev.openai.agentsmod;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

public final class Net {
    private Net() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AgentsMod.MOD_ID, path));
    }

    /** Cliente -> servidor: "apertei a tecla do slot N" (0=C, 1=Q, 2=E, 3=X). (dirX, dirZ) = direção de movimento desejada. */
    public record AbilityC2S(int slot, float dirX, float dirZ) implements CustomPacketPayload {
        public static final Type<AbilityC2S> TYPE = type("ability");
        public static final StreamCodec<RegistryFriendlyByteBuf, AbilityC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AbilityC2S::slot,
                ByteBufCodecs.FLOAT, AbilityC2S::dirX,
                ByteBufCodecs.FLOAT, AbilityC2S::dirZ,
                AbilityC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Cliente -> servidor: clique de disparo (só vale com um modo de tiro ativo, ex.: Blade Storm). */
    public record ShotC2S() implements CustomPacketPayload {
        public static final Type<ShotC2S> TYPE = type("shot");
        public static final StreamCodec<RegistryFriendlyByteBuf, ShotC2S> CODEC = StreamCodec.unit(new ShotC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Cliente -> servidor: equipar o agente de índice N (-1 = desligar). */
    public record SelectC2S(int agent) implements CustomPacketPayload {
        public static final Type<SelectC2S> TYPE = type("select");
        public static final StreamCodec<RegistryFriendlyByteBuf, SelectC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, SelectC2S::agent,
                SelectC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Servidor -> cliente dono: agente equipado, bits de desbloqueio, recargas (ticks) dos 4 slots e munição. */
    public record StateS2C(int selected, List<Integer> unlocked, List<Integer> cooldowns, int ammo)
            implements CustomPacketPayload {
        public static final Type<StateS2C> TYPE = type("state");
        public static final StreamCodec<RegistryFriendlyByteBuf, StateS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, StateS2C::selected,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), StateS2C::unlocked,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), StateS2C::cooldowns,
                ByteBufCodecs.VAR_INT, StateS2C::ammo,
                StateS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(AbilityC2S.TYPE, AbilityC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(ShotC2S.TYPE, ShotC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SelectC2S.TYPE, SelectC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(StateS2C.TYPE, StateS2C.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AbilityC2S.TYPE,
                (payload, context) -> AgentLogic.tryActivate(context.player(), payload.slot(), payload.dirX(), payload.dirZ()));
        ServerPlayNetworking.registerGlobalReceiver(ShotC2S.TYPE,
                (payload, context) -> AgentLogic.tryShoot(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(SelectC2S.TYPE,
                (payload, context) -> AgentLogic.select(context.player(), payload.agent()));
    }
}
