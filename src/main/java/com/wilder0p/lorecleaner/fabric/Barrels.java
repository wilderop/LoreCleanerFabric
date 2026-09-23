package com.wilder0p.lorecleaner.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;

import java.time.LocalDate;
import java.util.List;

final class Barrels {
    static BlockPos findAir(ServerLevel level, BlockPos origin) {
        if (valid(level, origin)) {
            return origin;
        }
        for (int r = 1; r <= 8; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) != r && Math.abs(z) != r) {
                        continue;
                    }
                    for (int y = -2; y <= 2; y++) {
                        BlockPos p = origin.offset(x, y, z);
                        if (valid(level, p)) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }

    static int place(ServerLevel level, BlockPos start, List<ItemStack> items, String name) {
        int barrels = 0;
        int index = 0;
        BlockPos current = start;
        while (index < items.size()) {
            if (!valid(level, current)) {
                BlockPos found = findAir(level, current);
                if (found == null) {
                    break;
                }
                current = found;
            }
            level.setBlock(current, Blocks.BARREL.defaultBlockState(), 3);
            if (level.getBlockEntity(current) instanceof BarrelBlockEntity barrel) {
                int slot = 0;
                while (index < items.size() && slot < 27) {
                    barrel.setItem(slot, items.get(index));
                    index++;
                    slot++;
                }
                barrel.setChanged();
            }
            placeSign(level, current, name);
            barrels++;
            current = current.offset(1, 0, 0);
        }
        return barrels;
    }

    private static void placeSign(ServerLevel level, BlockPos barrel, String name) {
        String date = LocalDate.now().toString();
        Direction[] faces = {Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST};
        for (Direction face : faces) {
            BlockPos sp = barrel.relative(face);
            if (!level.getBlockState(sp).isAir()) {
                continue;
            }
            BlockState state = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, face);
            level.setBlock(sp, state, 3);
            if (level.getBlockEntity(sp) instanceof SignBlockEntity sign) {
                Component[] lines = new Component[] {
                        Component.literal("LoreCleaner"),
                        Component.literal(name.length() > 16 ? name.substring(0, 16) : name),
                        Component.literal(date),
                        Component.empty()
                };
                sign.setText(new SignText(lines, lines, DyeColor.BLACK, false), true);
                sign.setChanged();
            }
            return;
        }
    }

    private static boolean valid(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        WorldBorder border = level.getWorldBorder();
        return border.isWithinBounds(pos.getX(), pos.getZ());
    }
}
