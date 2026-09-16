package com.zhichaoxi.applied_schematicannon.command;

import com.zhichaoxi.applied_schematicannon.AppliedSchematicannon;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Registers this mod's commands on the game event bus.
 */
@EventBusSubscriber(modid = AppliedSchematicannon.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ModCommands {

    private ModCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        DiagnoseCommand.register(event.getDispatcher());
    }
}
