package com.zhichaoxi.applied_schematicannon.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.blockentity.misc.InterfaceBlockEntity;
import com.simibubi.create.content.schematics.SchematicPrinter;
import com.simibubi.create.content.schematics.cannon.MaterialChecklist;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.cannon.SchematicannonInventory;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.mojang.logging.LogUtils;
import com.zhichaoxi.applied_schematicannon.me.MEInterfaceHelper;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Mixin(SchematicannonBlockEntity.class)
public abstract class SchematicannonBlockEntityMixin extends BlockEntity {

    @Unique
    private static final Logger LOGGER = LogUtils.getLogger();

    @Shadow protected abstract void refillFuelIfPossible();

    @Shadow public boolean hasCreativeCrate;
    @Shadow public int remainingFuel;

    @Shadow public abstract int getShotsPerGunpowder();

    @Shadow public boolean sendUpdate;
    @Shadow public SchematicannonInventory inventory;
    @Shadow public String statusMsg;
    @Shadow public int blocksPlaced;
    @Shadow public SchematicannonBlockEntity.State state;

    @Shadow protected abstract void findInventories();

    @Shadow public MaterialChecklist checklist;
    @Shadow public boolean skipMissing;
    @Shadow @Nullable public ItemStack missingItem;
    @Shadow public SchematicPrinter printer;

    @Shadow protected abstract boolean shouldPlace(BlockPos pos, BlockState state, @Nullable BlockEntity be,
                                                   BlockState toReplace, BlockState toReplaceOther,
                                                   boolean isNormalCube);

    /**
     * The ME Interfaces next to this cannon. Refreshed by {@link #findInventories$findMENetwork}.
     */
    @Unique
    private final ArrayList<InterfaceBlockEntity> appliedschematicannon$attachedMEInterfaces = new ArrayList<>();

    /**
     * Auto-crafting requests this cannon is waiting on, polled from {@link #tick} because AE2 plans crafting jobs on a
     * worker thread.
     */
    @Unique
    private final MEInterfaceHelper.PendingCraftingJobs appliedschematicannon$pendingCraftingJobs =
            new MEInterfaceHelper.PendingCraftingJobs();

    /**
     * Items already reported to the checklist from the ME network, so an item is never counted twice.
     */
    @Unique
    private final Set<Item> appliedschematicannon$gathered = new HashSet<>();

    /**
     * One-shot diagnostic flags. Each condition is reported once per cannon instead of every tick, so a cannon that
     * cannot draw from the ME network says why in the log without flooding it.
     */
    @Unique
    private boolean appliedschematicannon$reportedSkipMissing;

    /**
     * Items this cannon already reported as unrequestable, so each one is explained once.
     */
    @Unique
    private final Set<Item> appliedschematicannon$reportedUnrequestable = new HashSet<>();

    /**
     * When each item's ME network request was last reported, so a cannon stuck on the same block still shows up in the
     * log at a readable rate.
     */
    @Unique
    private final Map<Item, Long> appliedschematicannon$reportedRequests = new HashMap<>();

    /**
     * The cannon status that was reported last, so a status change is logged exactly once.
     */
    @Unique
    private String appliedschematicannon$lastReportedStatus = "<none>";

    /**
     * Items the cannon stalled on without knowing what is missing, reported once each.
     */
    @Unique
    private final Set<Item> appliedschematicannon$reportedStalls = new HashSet<>();

    /**
     * Guards against re-entering the stall check through the placement predicate.
     */
    @Unique
    private boolean appliedschematicannon$checkingStall;

    /**
     * How often a repeated request for the same item is re-reported, in ticks.
     */
    @Unique
    private static final long REQUEST_REPORT_INTERVAL = 200L;

    public SchematicannonBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    /**
     * Logs every status change of the cannon. The status is what the player sees in the cannon's GUI, so this makes
     * the log show exactly which branch the cannon is sitting in. A cannon reporting {@code searching} never reaches
     * the item-grabbing code at all, which is worth knowing before blaming the ME network.
     */
    @Unique
    private void appliedschematicannon$reportStatusChange() {
        String current = statusMsg;
        if (current == null || current.equals(appliedschematicannon$lastReportedStatus)) {
            return;
        }

        appliedschematicannon$lastReportedStatus = current;
        LOGGER.info("Cannon status is now '{}' (state={}, missingItem={}, adjacent ME interfaces={}) (at {})", current,
                state, missingItem == null ? "none" : missingItem.getHoverName().getString(),
                appliedschematicannon$attachedMEInterfaces.size(), worldPosition);
    }

    /**
     * Explains why the cannon is sitting in the {@code searching} status with a pending missing item.
     * <p>
     * Create's {@code tickPrinter} returns before it ever looks at the required items when the current requirement is
     * invalid or the blueprint cannot be placed at the current target, and sets the status to {@code searching}. In
     * that state the cannon never consults its inventories, so it also never asks the ME network for anything. This
     * check re-evaluates both conditions so the log can name the one that is holding the cannon back.
     */
    @Unique
    private void appliedschematicannon$checkStall() {
        if (missingItem == null || "missingBlock".equals(statusMsg)) {
            // A pending missing item is normal: it means the cannon is waiting for items, not stuck in searching.
            return;
        }
        if (appliedschematicannon$checkingStall || printer == null || !printer.isLoaded()) {
            return;
        }
        if (!appliedschematicannon$reportedStalls.add(missingItem.getItem())) {
            return;
        }

        String reason;
        appliedschematicannon$checkingStall = true;
        try {
            if (printer.getCurrentRequirement().isInvalid()) {
                reason = "the blueprint requires an item that cannot be represented, so Create rejects the target";
            } else if (printer.shouldPlaceCurrent(level, this::shouldPlace)) {
                reason = "the target is placeable after all";
            } else {
                reason = "the blueprint's current block cannot be placed here - check that the schematic is deployed in"
                        + " the world, that it is within the cannon's range, and that the replace mode allows"
                        + " replacing what is already there";
            }
        } finally {
            appliedschematicannon$checkingStall = false;
        }

        LOGGER.info("Cannon is stuck in '{}' for {}: {} (at {})", statusMsg,
                missingItem.getHoverName().getString(), reason, worldPosition);
    }

    /**
     * Logs a message once per cannon, for conditions a player can act on.
     */
    @Unique
    private void appliedschematicannon$reportSkipMissing(ItemStack stack) {
        if (appliedschematicannon$reportedSkipMissing) {
            return;
        }

        appliedschematicannon$reportedSkipMissing = true;
        LOGGER.info("The cannon is set to skip missing blocks, so {} is never requested from the ME network (at {})",
                stack.getHoverName().getString(), worldPosition);
    }

    /**
     * Logs why an item could not be requested, once per item and cannon.
     */
    @Unique
    private void appliedschematicannon$reportUnrequestable(ItemStack stack, @Nullable String reason) {
        if (!appliedschematicannon$reportedUnrequestable.add(stack.getItem())) {
            return;
        }

        if (reason == null) {
            LOGGER.info("The ME network was asked for {} but could not produce it (at {}) - check that a pattern is"
                    + " encoded and a crafting CPU is free", stack.getHoverName().getString(), worldPosition);
        } else {
            LOGGER.info("Cannot request {} from the ME network: {} (at {})", stack.getHoverName().getString(),
                    reason, worldPosition);
        }
    }

    /**
     * Reports which blocks the ME network was asked for and what came back. This is the entry point of the whole
     * feature: if nothing is logged here, the cannon never asked the ME network for anything.
     * <p>
     * The same item is re-reported at most once every {@link #REQUEST_REPORT_INTERVAL} ticks, so a cannon that sits on
     * the same missing block keeps showing up in the log instead of appearing only once.
     */
    @Unique
    private void appliedschematicannon$reportRequest(ItemStack stack, long needed, long found, boolean simulate,
                                                     boolean skipped, boolean committed) {
        long now = level == null ? 0 : level.getGameTime();
        Long last = appliedschematicannon$reportedRequests.get(stack.getItem());
        if (last != null && now - last < REQUEST_REPORT_INTERVAL) {
            return;
        }
        appliedschematicannon$reportedRequests.put(stack.getItem(), now);

        LOGGER.info("ME network request for {} x{} (found {}): simulate={} skipMissing={} craftingRequested={}"
                        + " interfaces={} (at {})", stack.getHoverName().getString(), needed, found, simulate, skipped,
                committed, appliedschematicannon$attachedMEInterfaces.size(), worldPosition);
    }

    /**
     * Reports everything the attached ME network can provide, so the cannon's checklist accounts for network items and
     * for items that still have to be synthesized, instead of only what sits in neighbouring inventories.
     */
    @Inject(method = "updateChecklist", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity;findInventories()V"))
    public void updateChecklist$collectFromMENetwork(CallbackInfo ci) {
        findInventories();
        appliedschematicannon$gathered.clear();

        for (InterfaceBlockEntity iface : appliedschematicannon$attachedMEInterfaces) {
            IGridNode node = appliedschematicannon$nodeOf(iface);
            if (node == null) {
                continue;
            }

            MEStorage storage = node.getGrid().getStorageService().getInventory();
            ICraftingService crafting = node.getGrid().getCraftingService();
            for (AEKey key : storage.getAvailableStacks().keySet()) {
                if (!(key instanceof AEItemKey itemKey)) {
                    continue;
                }

                long available = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.empty());
                if (available <= 0) {
                    continue;
                }

                // The checklist only cares about presence; clamping also keeps the amount cast from overflowing.
                ItemStack stack = itemKey.toStack((int) Math.min(available, Integer.MAX_VALUE));
                if (!stack.isEmpty()) {
                    checklist.collect(stack);
                    appliedschematicannon$gathered.add(itemKey.getReadOnlyStack().getItem());
                }
            }

            // Items the network can synthesize are not in it yet, but the cannon can still get them.
            for (AEKey key : crafting.getCraftables(AEItemKey.filter())) {
                if (!(key instanceof AEItemKey itemKey)) {
                    continue;
                }

                ItemStack stack = itemKey.getReadOnlyStack().copyWithCount(1);
                if (!stack.isEmpty() && appliedschematicannon$gathered.add(stack.getItem())) {
                    checklist.collect(stack);
                }
            }
        }
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity;refillFuelIfPossible()V"))
    public void tick$refillAndCraftFromMENetwork(CallbackInfo ci) {
        refillFuelIfPossible();
        appliedschematicannon$pollCraftingJobs();
        appliedschematicannon$refillFuelIfPossible();
    }

    /**
     * Explains a cannon that is stuck in {@code searching}. {@code tickPrinter} only runs on the server, so this is
     * where the player-visible state can be inspected on the game thread.
     */
    @Inject(method = "tickPrinter", at = @At("HEAD"))
    public void tickPrinter$reportState(CallbackInfo ci) {
        // Disabled: logging every status change is useful when diagnosing the cannon, but too chatty for normal play.
        // Re-enable this line when the cannon appears to be stuck.
        // appliedschematicannon$reportStatusChange();
        appliedschematicannon$checkStall();
    }

    @Unique
    protected void appliedschematicannon$refillFuelIfPossible() {
        if (hasCreativeCrate)
            return;
        if (remainingFuel > getShotsPerGunpowder()) {
            remainingFuel = getShotsPerGunpowder();
            sendUpdate = true;
            return;
        }

        if (remainingFuel > 0)
            return;

        if (!inventory.getStackInSlot(4)
                .isEmpty())
            inventory.getStackInSlot(4)
                    .shrink(1);
        else {
            boolean externalGunpowderFound = false;
            for (InterfaceBlockEntity iface : appliedschematicannon$attachedMEInterfaces) {
                IGridNode node = appliedschematicannon$nodeOf(iface);
                if (node == null) {
                    continue;
                }

                MEStorage storage = node.getGrid().getStorageService().getInventory();
                if (storage.extract(AEItemKey.of(Items.GUNPOWDER), 1, Actionable.MODULATE,
                        IActionSource.empty()) == 0)
                    continue;
                externalGunpowderFound = true;
                break;
            }
            if (!externalGunpowderFound)
                return;
        }

        remainingFuel += getShotsPerGunpowder();
        if (statusMsg.equals("noGunpowder")) {
            if (blocksPlaced > 0)
                state = SchematicannonBlockEntity.State.RUNNING;
            statusMsg = "ready";
        }
        sendUpdate = true;
    }

    /**
     * Finishes pending crafting calculations and hands their plans to a crafting CPU. Their output is delivered into
     * the ME network, so the cannon picks it up through {@link #grabItemsFromAttachedInventories$grabFromMENetwork} as
     * soon as it is available.
     */
    @Unique
    private void appliedschematicannon$pollCraftingJobs() {
        if (appliedschematicannon$pendingCraftingJobs.isEmpty()) {
            return;
        }

        // The player asked to skip missing blocks: nothing this cannon requests will be used any more.
        if (skipMissing) {
            appliedschematicannon$pendingCraftingJobs.clear();
            return;
        }

        MEInterfaceHelper.pollCraftingJobs(appliedschematicannon$attachedMEInterfaces,
                appliedschematicannon$pendingCraftingJobs);
    }
    @Inject(method = "findInventories", at = @At("RETURN"))
    public void findInventories$findMENetwork(CallbackInfo ci) {
        ArrayList<InterfaceBlockEntity> interfaces = appliedschematicannon$attachedMEInterfaces;
        interfaces.clear();
        for (Direction facing : Iterate.directions) {
            if (level == null || !level.isLoaded(worldPosition.relative(facing))) continue;

            BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(facing));
            if (blockEntity instanceof InterfaceBlockEntity iface) {
                interfaces.add(iface);
            }
        }
    }

    @Inject(method = "grabItemsFromAttachedInventories", at = @At("TAIL"), cancellable = true)
    public void grabItemsFromAttachedInventories$grabFromMENetwork(ItemRequirement.StackRequirement required,
                                                                   boolean simulate,
                                                                   CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            return;
        }

        ArrayList<InterfaceBlockEntity> interfaces = appliedschematicannon$attachedMEInterfaces;
        IActionSource actionSource = IActionSource.empty();
        ItemRequirement.ItemUseType usage = required.usage;

        // Tools are not consumed: use one from the network and store it back once it took a point of damage.
        if (usage == ItemRequirement.ItemUseType.DAMAGE) {
            if (appliedschematicannon$damageToolFromMENetwork(interfaces, required, simulate, actionSource)) {
                cir.setReturnValue(true);
            }
            return;
        }

        long requiredAmount = required.stack.getCount();
        AEItemKey key = AEItemKey.of(required.stack);
        if (key == null) {
            cir.setReturnValue(false);
            return;
        }

        long found = MEInterfaceHelper.simulateExtract(interfaces, key, requiredAmount, actionSource);
        if (found < requiredAmount) {
            // Missing items are requested from the network instead of being written into an interface's config slot.
            //
            // This has to happen on the simulating call: Create aborts the whole placement with "missingBlock" as soon
            // as the simulating pass reports a shortfall, so the pass that actually commits (simulate == false) is
            // never reached while items are missing. Requesting here is what makes the cannon ask the ME network at
            // all; repeated requests are harmless because MEInterfaceHelper only starts a calculation when nothing is
            // queued or already in flight for this item.
            boolean requested = false;
            if (skipMissing) {
                appliedschematicannon$reportSkipMissing(required.stack);
            } else {
                // Ask for everything the rest of this schematic still needs of that item, not just the one stack that
                // is blocking the current block. Requesting a single item per placement makes an auto-crafting job
                // start and finish for every single block, which is far slower than crafting the rest in one go.
                long batch = Math.max(requiredAmount - found, appliedschematicannon$remainingRequirement(required.stack));
                requested = MEInterfaceHelper.requestCrafting(interfaces, level,
                        appliedschematicannon$pendingCraftingJobs, key, batch,
                        appliedschematicannon$actionHostOf(interfaces));
                if (!requested) {
                    appliedschematicannon$reportUnrequestable(required.stack,
                            MEInterfaceHelper.describeRequestFailure(interfaces, key));
                }
            }
            appliedschematicannon$reportRequest(required.stack, requiredAmount, found, simulate, skipMissing,
                    requested);
            cir.setReturnValue(false);
            return;
        }

        if (!simulate) {
            MEInterfaceHelper.extract(interfaces, key, requiredAmount, actionSource);
        }
        appliedschematicannon$reportRequest(required.stack, requiredAmount, found, simulate, skipMissing, false);
        cir.setReturnValue(true);
    }

    /**
     * @return how many more of {@code stack} the rest of this schematic needs, or {@code 0} when the cannon has no
     * requirement data to go by.
     * <p>
     * Create collects this while building the material checklist for the blocks it still has to place, and
     * {@code getRequiredAmount} already subtracts what {@code updateChecklist} gathered from the network, so the value
     * is the remaining shortfall. It shrinks as the cannon makes progress.
     */
    @Unique
    private long appliedschematicannon$remainingRequirement(ItemStack stack) {
        if (checklist == null || stack.isEmpty()) {
            return 0;
        }

        return Math.max(0, checklist.getRequiredAmount(stack.getItem()));
    }

    /**
     * Takes one matching tool out of the ME network, applies a point of damage to it and stores it back, mirroring how
     * Create consumes tools from neighbouring inventories.
     */
    @Unique
    private boolean appliedschematicannon$damageToolFromMENetwork(ArrayList<InterfaceBlockEntity> interfaces,
                                                                  ItemRequirement.StackRequirement required,
                                                                  boolean simulate,
                                                                  IActionSource actionSource) {
        for (InterfaceBlockEntity iface : interfaces) {
            IGridNode node = appliedschematicannon$nodeOf(iface);
            if (node == null) {
                continue;
            }

            MEStorage storage = node.getGrid().getStorageService().getInventory();
            for (AEKey key : storage.getAvailableStacks().keySet()) {
                if (!(key instanceof AEItemKey itemKey)) {
                    continue;
                }

                ItemStack candidate = itemKey.toStack();
                if (!required.matches(candidate) || !candidate.isDamageableItem()) {
                    continue;
                }

                if (simulate) {
                    return true;
                }

                if (storage.extract(key, 1, Actionable.MODULATE, actionSource) == 0) {
                    continue;
                }

                ItemStack damaged = candidate.copyWithCount(1);
                damaged.setDamageValue(damaged.getDamageValue() + 1);
                if (damaged.getDamageValue() <= damaged.getMaxDamage()) {
                    storage.insert(AEItemKey.of(damaged), damaged.getCount(),
                            Actionable.MODULATE, actionSource);
                }
                return true;
            }
        }
        return false;
    }

    @Unique
    private static IGridNode appliedschematicannon$nodeOf(InterfaceBlockEntity iface) {
        return iface == null ? null : iface.getInterfaceLogic().getActionableNode();
    }

    /**
     * @return an attached interface to report crafting requests under. AE2 derives the grid node it plans a job on
     * from this host, so it must not be {@code null}.
     */
    @Unique
    private static IActionHost appliedschematicannon$actionHostOf(ArrayList<InterfaceBlockEntity> interfaces) {
        for (InterfaceBlockEntity iface : interfaces) {
            if (iface != null && appliedschematicannon$nodeOf(iface) != null) {
                return iface;
            }
        }
        return null;
    }
}
