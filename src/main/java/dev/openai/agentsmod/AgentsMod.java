package dev.openai.agentsmod;

import dev.openai.agentsmod.Defs.AbilityDef;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class AgentsMod implements ModInitializer {
    public static final String MOD_ID = "agentsmod";

    /** Um item de desbloqueio por habilidade (120 no total), na mesma ordem de Defs.ABILITIES. */
    public static final List<Item> UNLOCK_ITEMS = new ArrayList<>();

    static {
        for (AbilityDef def : Defs.ABILITIES) {
            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, def.id));
            // Desde 1.21.2 o id do item precisa estar nas Properties antes de criar o Item.
            Item item = new UnlockItem(new Item.Properties().setId(key).stacksTo(16), def);
            UNLOCK_ITEMS.add(Registry.register(BuiltInRegistries.ITEM, key, item));
        }
    }

    public static final Item CONTRACT;

    static {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "reassignment_contract"));
        CONTRACT = Registry.register(BuiltInRegistries.ITEM, key, new ContractItem(new Item.Properties().setId(key).stacksTo(16)));
    }

    @Override
    public void onInitialize() {
        Net.register();
        AgentLogic.init();
        ResourceKey<CreativeModeTab> tabKey = ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, tabKey, FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.agentsmod.main"))
                .icon(() -> new ItemStack(UNLOCK_ITEMS.get(0)))
                .displayItems((params, output) -> {
                    output.accept(CONTRACT);
                    UNLOCK_ITEMS.forEach(output::accept);
                })
                .build());
    }
}
