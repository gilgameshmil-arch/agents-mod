package dev.openai.agentsmod;

import dev.openai.agentsmod.Defs.AbilityDef;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Item craftável que, ao ser usado UMA vez (botão direito), desbloqueia a habilidade de forma permanente.
 * Depois do desbloqueio, a habilidade é usada só pela tecla: não é preciso segurar nada.
 */
public class UnlockItem extends Item {
    private final AbilityDef ability;

    public UnlockItem(Properties properties, AbilityDef ability) {
        super(properties);
        this.ability = ability;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        ItemStack stack = player.getItemInHand(hand);
        if (AgentLogic.isUnlocked(sp, ability)) {
            sp.displayClientMessage(Component.translatable("message.agentsmod.already_unlocked",
                    Component.translatable(ability.nameKey())), true);
            return InteractionResult.FAIL;
        }
        if (!AgentLogic.canUnlock(sp, ability)) {
            sp.displayClientMessage(Component.translatable("message.agentsmod.wrong_agent",
                    Component.translatable(ability.agent.nameKey())), true);
            return InteractionResult.FAIL;
        }
        AgentLogic.unlock(sp, ability);
        stack.consume(1, player);
        sp.displayClientMessage(Component.translatable("message.agentsmod.unlocked",
                Component.translatable(ability.nameKey())), true);
        return InteractionResult.SUCCESS;
    }
}
