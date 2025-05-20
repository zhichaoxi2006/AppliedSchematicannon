package com.zhichaoxi.applied_schematicannon.mixin;

import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.blockentity.misc.InterfaceBlockEntity;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

@Mixin(SchematicannonBlockEntity.class)
public abstract class SchematicannonBlockEntityMixin extends BlockEntity {

    @Unique
    protected ArrayList<IGridNode> createOddities$attachedMENetwork = new ArrayList<>();

    public SchematicannonBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    @Inject(method = "findInventories", at = @At("RETURN"))
    public void findInventories$findMENetwork(CallbackInfo ci) {
        createOddities$attachedMENetwork.clear();
        for (Direction facing : Iterate.directions) {

            if (level != null && !level.isLoaded(worldPosition.relative(facing))) continue;

            BlockEntity blockEntity = null;
            if (level != null) {
                blockEntity = level.getBlockEntity(worldPosition.relative(facing));
            }
            if (blockEntity instanceof InterfaceBlockEntity) {
                IInWorldGridNodeHost capability =
                        level.getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST,
                                blockEntity.getBlockPos());
                if (capability != null) {
                    IGridNode gridNode =  capability.getGridNode(facing.getOpposite());
                    if (gridNode != null) {
                        createOddities$attachedMENetwork.add(gridNode);
                    }
                }
            }
        }
    }

    @Inject(method = "grabItemsFromAttachedInventories", at = @At("HEAD"), cancellable = true)
    public void grabItemsFromAttachedInventories$grabFromMENetwork(ItemRequirement.StackRequirement required, boolean simulate,
                                                                   CallbackInfoReturnable<Boolean> cir) {
        ItemRequirement.ItemUseType usage = required.usage;

        if (usage == ItemRequirement.ItemUseType.DAMAGE) {
            for (IGridNode cap : createOddities$attachedMENetwork) {
                if (cap != null) {
                    MEStorage storage = cap.getGrid()
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
        for (IGridNode cap : createOddities$attachedMENetwork) {
            if (cap != null)
            {
                MEStorage storage = cap.getGrid()
                        .getStorageService().getInventory();
                amountFound += storage.extract(AEItemKey.of(required.stack),
                        required.stack.getCount(), Actionable.SIMULATE, null);
            }
            if (amountFound < required.stack.getCount())
            {
                continue;
            }

            success = true;
            break;
        }

        if (!simulate && success) {
            amountFound = 0;
            for (IGridNode cap : createOddities$attachedMENetwork) {
                if (cap != null)
                {
                    MEStorage storage = cap.getGrid()
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
