package dev.openai.agentsmod;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Contrato de Reatribuição: retira o agente atual (as habilidades já desbloqueadas continuam salvas) e abre o loadout
 * para escolher outro agente. Consome 1 contrato. Sem agente ativo, só abre o loadout (sem gastar).
 */
public class ContractItem extends Item {
    public ContractItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        ItemStack stack = player.getItemInHand(hand);
        if (AgentLogic.retireAgent(sp)) {
            stack.consume(1, player);
            sp.displayClientMessage(Component.translatable("message.agentsmod.contract_used"), true);
        } else {
            sp.displayClientMessage(Component.translatable("message.agentsmod.contract_none"), true);
        }
        if (ServerPlayNetworking.canSend(sp, Net.OpenLoadoutS2C.TYPE)) ServerPlayNetworking.send(sp, new Net.OpenLoadoutS2C());
        return InteractionResult.SUCCESS;
    }
}
