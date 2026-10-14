package com.wincraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.core.Registry;

public final class WinCraftMod implements ModInitializer {
    public static final String ID = "wincraft";
    private static final ResourceKey<Item> DESKTOP_LAUNCHER_KEY = ResourceKey.create(Registries.ITEM,
        Identifier.fromNamespaceAndPath(ID, "desktop_launcher"));
    public static final Item DESKTOP_LAUNCHER = registerItem("desktop_launcher",
        new DesktopLauncherItem(new Item.Properties().setId(DESKTOP_LAUNCHER_KEY)));

    private static Item registerItem(String name, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ID, name), item);
    }

    @Override public void onInitialize() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
            .register(output -> output.accept(DESKTOP_LAUNCHER));
    }
}
