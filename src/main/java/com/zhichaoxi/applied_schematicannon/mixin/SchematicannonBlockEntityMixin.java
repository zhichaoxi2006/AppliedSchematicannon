package com.zhichaoxi.applied_schematicannon.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.blockentity.misc.InterfaceBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.helpers.InterfaceLogic;
import com.simibubi.create.content.schematics.cannon.MaterialChecklist;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.cannon.SchematicannonInventory;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

@Mixin(SchematicannonBlockEntity.class)
public abstract class SchematicannonBlockEntityMixin extends BlockEntity {

    @Shadow protected abstract void refillFuelIfPossible();

    @Shadow public boolean hasCreativeCrate;
    @Shadow public int remainingFuel;

    @Shadow public abstract int getShotsPerGunpowder();

    @Shadow public boolean sendUpdate;
    @Shadow public SchematicannonInventory inventory;
    @Shadow public String statusMsg;
    @Shadow public int blocksPlaced;
    @Shadow public SchematicannonBlockEntity.State state;

    @Shadow public abstract void findInventories();

    @Shadow public MaterialChecklist checklist;
    @Shadow public boolean skipMissing;
    @Unique protected ArrayList<InterfaceBlockEntity> SchematicannonBlockEntityMixin$attachedMEInterface = new ArrayList<>();

    public SchematicannonBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    @Inject(method = "updateChecklist", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity;findInventories()V"))
    public void SchematicannonBlockEntityMixin$updateChecklist(CallbackInfo ci) {
        findInventories();
        for (InterfaceBlockEntity be : SchematicannonBlockEntityMixin$attachedMEInterface) {
            if (be == null)
                continue;
            IGridNode node = be.getInterfaceLogic().getActionableNode();
            if (node == null) {
                continue;
            }
            MEStorage storage = node.getGrid()
                    .getStorageService().getInventory();
            var set = storage.getAvailableStacks().keySet();
            for(AEKey key : set) {
                if (key instanceof AEItemKey)
                {
                    long amount = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, null);
                    ItemStack stack = ((AEItemKey) key).toStack((int) amount);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    checklist.collect(stack);
                }
            }
        }
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity;refillFuelIfPossible()V"))
    public void tick$refillFromMENetwork(CallbackInfo ci) {
        refillFuelIfPossible();
        SchematicannonBlockEntityMixin$refillFuelIfPossible();
    }

    @Unique
    protected void SchematicannonBlockEntityMixin$refillFuelIfPossible() {
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
            for (InterfaceBlockEntity be : SchematicannonBlockEntityMixin$attachedMEInterface) {
                IGridNode node = be.getInterfaceLogic().getActionableNode();
                if (node == null) {
                    continue;
                }
                MEStorage storage = node.getGrid().getStorageService()
                        .getInventory();

                if (storage.extract(AEItemKey.of(Items.GUNPOWDER), 1, Actionable.MODULATE, null) == 0)
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

    @Inject(method = "findInventories", at = @At("RETURN"))
    public void findInventories$findMENetwork(CallbackInfo ci) {
        SchematicannonBlockEntityMixin$attachedMEInterface.clear();
        for (Direction facing : Iterate.directions) {

            if (level != null && !level.isLoaded(worldPosition.relative(facing))) continue;

            BlockEntity blockEntity = null;
            if (level != null) {
                blockEntity = level.getBlockEntity(worldPosition.relative(facing));
            }
            if (blockEntity instanceof InterfaceBlockEntity) {
                SchematicannonBlockEntityMixin$attachedMEInterface.add((InterfaceBlockEntity) blockEntity);
            }
        }
    }

    @Inject(method = "grabItemsFromAttachedInventories", at = @At("TAIL"), cancellable = true)
    public void grabItemsFromAttachedInventories$grabFromMENetwork(ItemRequirement.StackRequirement required, boolean simulate,
                                                                   CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            return;
        }
        ItemRequirement.ItemUseType usage = required.usage;

        if (usage == ItemRequirement.ItemUseType.DAMAGE) {
            for (InterfaceBlockEntity be : SchematicannonBlockEntityMixin$attachedMEInterface) {
                if (be != null) {
                    IGridNode node = be.getInterfaceLogic().getActionableNode();
                    if (node == null) {
                        continue;
                    }
                    MEStorage storage = node.getGrid()
                            .getStorageService().getInventory();
                    var set = storage.getAvailableStacks().keySet();
                    for(AEKey key : set) {
                        if (key instanceof AEItemKey)
                        {
                            ItemStack extractItem = ((AEItemKey) key).toStack();
                            if (!required.matches(extractItem))
                                continue;
                            if (!extractItem.isDamageableItem())
                                continue;

                            if (!simulate) {
                                long amount = storage.extract(key, 1, Actionable.MODULATE, null);
                                ItemStack stack = new ItemStack(((AEItemKey) key).getItem(), (int) amount);
                                stack.setDamageValue(stack.getDamageValue() + 1);
                                if (stack.getDamageValue() <= stack.getMaxDamage()) {
                                    storage.insert(AEItemKey.of(stack), stack.getCount(), Actionable.MODULATE, null);
                                }
                            }

                            cir.setReturnValue(true);
                        }
                    }
                }
            }

            cir.setReturnValue(false);
        }

        // Find and remove
        boolean success = false;
        long amountFound = 0;
        for (InterfaceBlockEntity be : SchematicannonBlockEntityMixin$attachedMEInterface) {
            InterfaceLogic logic = be.getInterfaceLogic();
            IGridNode node = logic.getActionableNode();
            if (node == null) {
                continue;
            }

            MEStorage storage = node.getGrid()
                    .getStorageService().getInventory();
            amountFound += storage.extract(AEItemKey.of(required.stack),
                    required.stack.getCount(), Actionable.SIMULATE, null);
            if (amountFound < required.stack.getCount())
            {
                if (!skipMissing && logic.getInstalledUpgrades(AEItems.CRAFTING_CARD.asItem()) > 0) {
                    ItemStack stack = required.stack.copy();
                    stack.setCount(64);
                    logic.getConfig().setStack(0, GenericStack.fromItemStack(stack));
                }
                continue;
            }

            success = true;
            break;
        }

        if (!simulate && success) {
            amountFound = 0;
            for (InterfaceBlockEntity be : SchematicannonBlockEntityMixin$attachedMEInterface) {
                if (be != null) {
                    IGridNode node = be.getInterfaceLogic().getActionableNode();
                    if (node == null) {
                        continue;
                    }
                    MEStorage storage = node.getGrid()
                            .getStorageService().getInventory();
                    amountFound += storage.extract(AEItemKey.of(required.stack),
                            required.stack.getCount(), Actionable.MODULATE, null);
                }
                if (amountFound < required.stack.getCount())
                {
                    continue;
                }
                break;
            }
        }

        cir.setReturnValue(success);
    }
}
