package com.zhichaoxi.applied_schematicannon.command;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.blockentity.misc.InterfaceBlockEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import com.zhichaoxi.applied_schematicannon.me.MEInterfaceHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reports what the ME network around the command source thinks about one item:
 * <ul>
 *     <li>how much of it is stored,</li>
 *     <li>which crafting patterns produce it,</li>
 *     <li>which patterns consume it,</li>
 *     <li>how many crafting CPUs the network has,</li>
 *     <li>and what a real crafting calculation reports as missing.</li>
 * </ul>
 * AE2 answers "no pattern", "no crafting CPU" and "ingredients missing" with the exact same result, which makes a
 * failed auto-crafting request impossible to diagnose from the outside; this command exposes the underlying facts.
 */
public final class DiagnoseCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DiagnoseCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("appliedschematicannon")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("diagnose")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .executes(ctx -> diagnose(ctx, 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> diagnose(ctx, IntegerArgumentType.getInteger(ctx, "amount")))))));
    }

    private static int diagnose(CommandContext<CommandSourceStack> ctx, int amount) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());

        ResourceLocation id = ResourceLocation.tryParse(StringArgumentType.getString(ctx, "item"));
        if (id == null) {
            source.sendFailure(Component.literal("Not a valid item id: " + StringArgumentType.getString(ctx, "item")));
            return 0;
        }

        Item item = BuiltInRegistries.ITEM.get(id);
        AEItemKey what = AEItemKey.of(new ItemStack(item));
        if (what == null) {
            source.sendFailure(Component.literal("Unknown item: " + id));
            return 0;
        }

        List<InterfaceBlockEntity> interfaces = new ArrayList<>();
        for (Direction facing : Direction.values()) {
            BlockEntity be = level.getBlockEntity(pos.relative(facing));
            if (be instanceof InterfaceBlockEntity iface) {
                interfaces.add(iface);
            }
        }

        reply(source, "Position " + pos.toShortString() + ", adjacent ME interfaces: " + interfaces.size());
        if (interfaces.isEmpty()) {
            reply(source, "Stand next to an ME Interface (or place one next to you) and run this again.");
            return 0;
        }

        IGrid grid = MEInterfaceHelper.gridOf(interfaces.get(0));
        if (grid == null) {
            reply(source, "The adjacent ME Interface is not connected to a network.");
            return 0;
        }

        ICraftingService crafting = grid.getCraftingService();
        MEStorage storage = grid.getStorageService().getInventory();
        reply(source, "Stored: " + storage.extract(what, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE,
                IActionSource.empty()));

        var produces = crafting.getCraftingFor(what);
        reply(source, "Patterns producing this item: " + produces.size());
        for (IPatternDetails pattern : produces) {
            reply(source, "  output " + describe(pattern.getOutputs()) + " <- " + describeInputs(pattern));
        }

        List<String> consumers = new ArrayList<>();
        for (AEKey candidate : crafting.getCraftables(AEItemKey.filter())) {
            for (IPatternDetails pattern : crafting.getCraftingFor(candidate)) {
                for (IPatternDetails.IInput input : pattern.getInputs()) {
                    for (GenericStack possible : input.getPossibleInputs()) {
                        if (possible.what().equals(what)) {
                            consumers.add(describe(pattern.getOutputs()) + " (uses " + possible.amount()
                                    + " per craft)");
                            break;
                        }
                    }
                }
            }
        }
        reply(source, "Patterns consuming this item: " + consumers.size());
        for (String entry : consumers) {
            reply(source, "  " + entry);
        }

        reply(source, "Crafting CPUs on this network: " + crafting.getCpus().size());
        reply(source, "isCraftable(this item) = " + crafting.isCraftable(what));
        reply(source, "Network holds: " + MEInterfaceHelper.describeInventory(interfaces));

        try {
            // The simulation requester must resolve a grid node. Without one AE2's crafting tree skips every pattern
            // and reports the requested item itself as missing.
            IActionSource simSource = IActionSource.ofMachine(interfaces.get(0));
            ICraftingSimulationRequester requester = () -> simSource;
            ICraftingPlan plan = crafting
                    .beginCraftingCalculation(level, requester, what, amount, CalculationStrategy.CRAFT_LESS)
                    .get(15, TimeUnit.SECONDS);

            reply(source, "Calculation: simulation=" + plan.simulation() + ", bytes=" + plan.bytes());
            for (var entry : plan.missingItems()) {
                reply(source, "  MISSING " + entry.getKey() + " x" + entry.getLongValue());
            }
            for (var entry : plan.usedItems()) {
                reply(source, "  would use " + entry.getKey() + " x" + entry.getLongValue());
            }
        } catch (Exception e) {
            reply(source, "Calculation failed: " + e);
        }

        return 1;
    }

    private static String describeInputs(IPatternDetails pattern) {
        StringBuilder builder = new StringBuilder();
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible.length == 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" + ");
            }
            builder.append(possible[0].what()).append(" x").append(possible[0].amount());
            if (possible.length > 1) {
                builder.append(" (or ").append(possible.length - 1).append(" substitute(s))");
            }
        }
        return builder.isEmpty() ? "nothing" : builder.toString();
    }

    private static String describe(List<GenericStack> stacks) {
        StringBuilder builder = new StringBuilder();
        for (GenericStack stack : stacks) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(stack.what()).append(" x").append(stack.amount());
        }
        return builder.isEmpty() ? "nothing" : builder.toString();
    }

    private static void reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        LOGGER.debug("[diagnose] {}", message);
    }
}
