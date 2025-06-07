package comm.ishland.c2me.rewrites.chunksystem.common.quirks;


import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

public class FlowableFluidUtils {

    public static final ThreadLocal<Boolean> shouldntFlow = new ThreadLocal<>();
    public static final ThreadLocal<Boolean> hadFlown = new ThreadLocal<>();

    public static boolean needsPostProcessing(LevelReader world, BlockPos pos, BlockState blockState, FluidState fluidState) {
        if (!fluidState.isSource()) {
            return true;
        }
        return canFlowNormally(world, pos, blockState, fluidState);
    }

    private static boolean canFlowNormally(LevelReader world, BlockPos pos, BlockState blockState, FluidState fluidState) {
        if (fluidState.isEmpty()) return false;

        BlockPos belowPos = pos.below();
        BlockState belowBlockState = world.getBlockStateIfLoaded(belowPos);
        if (belowBlockState == null)
            return false;
        FluidState belowFluidState = world.getFluidState(belowPos);
        // very rough filtering
        if (canFlowThrough1_21_5((FlowingFluid) fluidState.getType(), world, pos, blockState, Direction.DOWN, belowPos, belowBlockState, belowFluidState)) {
            FluidState fluidState3 = getUpdatedState(((FlowingFluid) fluidState.getType()), world, belowPos, belowBlockState);
            if (fluidState3 == null) {
                return true; // shortcut
            }
            Fluid fluid = fluidState3.getType();
            if (belowFluidState.canBeReplacedWith(world, belowPos, fluid, Direction.DOWN) && canFillWithFluid1_21_5(world, belowPos, belowBlockState, fluid)) {
                return true;
            }
        }
        if (canSpreadToSidesNormally(world, pos, blockState, fluidState)) { // fluid always still when reached here
            return true;
        }

        return false;
    }

    private static boolean canSpreadToSidesNormally(LevelReader world, BlockPos pos, BlockState blockState, FluidState fluidState) {
        int nextFluidLevel = fluidState.getAmount() - ((FlowingFluid) fluidState.getType()).getDropOff(world);
        if (fluidState.getValue(FlowingFluid.FALLING)) {
            nextFluidLevel = 7;
        }
        if (nextFluidLevel > 0) {
            // getSpread
//            int i = 1000;
//            Map<Direction, FluidState> map = Maps.newEnumMap(Direction.class);
//            SpreadCache spreadCache = null;

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos offsetPos = pos.relative(direction);
                BlockState offsetBlockState = world.getBlockState(offsetPos);
                FluidState offsetFluidState = offsetBlockState.getFluidState();
                if (canFlowThrough1_21_5((FlowingFluid) fluidState.getType(), world, pos, blockState, direction, offsetPos, offsetBlockState, offsetFluidState)) {
//                    FluidState fluidState2 = getUpdatedState((FlowableFluid) fluidState.getFluid(), world, offsetPos, offsetBlockState);
//                    if (fluidState2 == null) {
//                        return true; // shortcut
//                    }
//                    if (canFillWithFluid1_21_5(world, offsetPos, offsetBlockState, fluidState2.getFluid())) {
//                        return true; // shortcut
//                    }
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean canFlowThrough1_21_5(FlowingFluid receiver, BlockGetter world, BlockPos pos, BlockState state, Direction face, BlockPos fromPos, BlockState fromState, FluidState fluidState) {
        return !receiver.isSourceBlockOfThisType(fluidState) &&
                canFillShort(fromState) &&
                receiver.canPassThroughWall(face, world, pos, state, fromPos, fromState);
    }

    private static boolean canFill(BlockGetter world, BlockPos pos, BlockState state, Fluid fluid) {
        return canFillShort(state) && canFillWithFluid1_21_5(world, pos, state, fluid);
    }

    private static boolean canFillWithFluid1_21_5(BlockGetter world, BlockPos pos, BlockState state, Fluid fluid) {
        return state.getBlock() instanceof LiquidBlockContainer fluidFillable ? fluidFillable.canPlaceLiquid(null, world, pos, state, fluid) : true;
    }

    private static boolean canFlowDownTo(FlowingFluid receiver, BlockGetter world, BlockPos pos, BlockState state, BlockPos fromPos, BlockState fromState) {
        if (!receiver.canPassThroughWall(Direction.DOWN, world, pos, state, fromPos, fromState)) {
            return false;
        } else {
            return fromState.getFluidState().getType().isSame(receiver) ? true : canFill(world, fromPos, fromState, receiver.getFlowing());
        }
    }

    private static boolean canFillShort(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof LiquidBlockContainer) {
            return true; // shortcut
        } else if (!(block instanceof DoorBlock)
                && !state.is(BlockTags.SIGNS)
                && !state.is(Blocks.LADDER)
                && !state.is(Blocks.SUGAR_CANE)
                && !state.is(Blocks.BUBBLE_COLUMN)) {
            return !state.is(Blocks.NETHER_PORTAL) &&
                    !state.is(Blocks.END_PORTAL) &&
                    !state.is(Blocks.END_GATEWAY) &&
                    !state.is(Blocks.STRUCTURE_VOID)
                    ? !state.blocksMotion()
                    : false;
        } else {
            return false;
        }
    }

    private static FluidState getUpdatedState(FlowingFluid receiver, LevelReader world, BlockPos pos, BlockState state) {
        int i = 0;
        int j = 0;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos blockPos = mutable.setWithOffset(pos, direction);
            BlockState blockState = world.getBlockState(blockPos);
            FluidState fluidState = blockState.getFluidState();
            if (fluidState.getType().isSame(receiver) && receiver.canPassThroughWall(direction, world, pos, state, blockPos, blockState)) {
                if (fluidState.isSource()) {
                    j++;
                }

                i = Math.max(i, fluidState.getAmount());
            }
        }

        if (j >= 2) {
            return null; // to not filter this
        }

        BlockPos blockPos2 = mutable.setWithOffset(pos, Direction.UP);
        BlockState blockState3 = world.getBlockState(blockPos2);
        FluidState fluidState3 = blockState3.getFluidState();
        if (!fluidState3.isEmpty() && fluidState3.getType().isSame(receiver) && receiver.canPassThroughWall(Direction.UP, world, pos, state, blockPos2, blockState3)) {
            return receiver.getFlowing(8, true);
        } else {
            int k = i - receiver.getDropOff(world);
            return k <= 0 ? Fluids.EMPTY.defaultFluidState() : receiver.getFlowing(k, false);
        }
    }

}
