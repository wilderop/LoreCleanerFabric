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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds safe air near logout and places barrel(s) + wall signs.
 *
 * F1: placement reports {@link PlacementResult#complete}; callers must abort
 * before the .dat save when false. F11: only already-loaded chunks are touched
 * and (optionally) protection claims are skipped.
 */
final class Barrels {
    private static final Logger LOG = LoggerFactory.getLogger("LoreCleaner");

    // Lazily-detected protection mods (F11).
    private static boolean flanChecked, flanPresent, ftbChecked, ftbPresent;
    private static final java.util.Set<String> protectionApiWarned = new java.util.HashSet<>();

    /** Result of a barrel placement run. {@link #complete} is false when air ran out. */
    static final class PlacementResult {
        final int barrelsPlaced;
        final int itemsPlaced;
        final boolean complete;
        final List<PlacedBlock> placedBlocks;

        PlacementResult(int barrelsPlaced, int itemsPlaced, boolean complete, List<PlacedBlock> placedBlocks) {
            this.barrelsPlaced = barrelsPlaced;
            this.itemsPlaced = itemsPlaced;
            this.complete = complete;
            this.placedBlocks = placedBlocks;
        }
    }

    /** A block this placement run created (barrel or sign), for rollback. */
    record PlacedBlock(ServerLevel level, BlockPos pos) {}

    /**
     * Remove every barrel/sign this placement run created. Only reverts blocks
     * that still hold what we placed (never touches blocks another process changed).
     */
    static void rollbackPlacement(PlacementResult result) {
        if (result == null || result.placedBlocks == null) {
            return;
        }
        for (PlacedBlock p : result.placedBlocks) {
            try {
                BlockState st = p.level().getBlockState(p.pos());
                if (st.is(Blocks.BARREL) || st.getBlock() instanceof WallSignBlock) {
                    p.level().setBlock(p.pos(), Blocks.AIR.defaultBlockState(), 3);
                }
            } catch (Exception ignored) {
            }
        }
    }

    static BlockPos findAir(ServerLevel level, BlockPos origin, boolean skipProtected) {
        if (allowed(level, origin, skipProtected)) {
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
                        if (allowed(level, p, skipProtected)) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * @return placement result; check {@link PlacementResult#complete} — when false,
     * some items were NOT placed and the caller must abort before saving playerdata.
     * Any exception mid-placement rolls back partial work before propagating.
     */
    static PlacementResult place(ServerLevel level, BlockPos start, List<ItemStack> items,
                                String name, boolean skipProtected) {
        List<PlacedBlock> placed = new ArrayList<>();
        try {
            return placeInner(level, start, items, name, skipProtected, placed);
        } catch (Exception e) {
            // F8: roll back whatever was placed before the failure, then propagate.
            rollbackPlacement(new PlacementResult(0, 0, false, placed));
            throw e instanceof RuntimeException re ? re : new RuntimeException("barrel placement failed", e);
        }
    }

    private static PlacementResult placeInner(ServerLevel level, BlockPos start, List<ItemStack> items,
                                              String name, boolean skipProtected, List<PlacedBlock> placed) {
        int barrels = 0;
        int index = 0;
        boolean complete = true;
        BlockPos current = start;
        while (index < items.size()) {
            if (!allowed(level, current, skipProtected)) {
                BlockPos found = findAir(level, current, skipProtected);
                if (found == null) {
                    // F1: do NOT silently drop the remaining items — report
                    // incomplete so the caller aborts before the .dat save.
                    LOG.error("LoreCleaner ran out of free air blocks placing barrels for {} — {} item(s) NOT placed",
                            name, items.size() - index);
                    complete = false;
                    break;
                }
                current = found;
            }
            level.setBlock(current, Blocks.BARREL.defaultBlockState(), 3);
            placed.add(new PlacedBlock(level, current.immutable()));
            if (level.getBlockEntity(current) instanceof BarrelBlockEntity barrel) {
                int slot = 0;
                while (index < items.size() && slot < 27) {
                    barrel.setItem(slot, items.get(index));
                    index++;
                    slot++;
                }
                barrel.setChanged();
            }
            placeSign(level, current, name, skipProtected, placed);
            barrels++;
            current = current.offset(1, 0, 0);
        }
        return new PlacementResult(barrels, index, complete, placed);
    }

    private static void placeSign(ServerLevel level, BlockPos barrel, String name,
                                boolean skipProtected, List<PlacedBlock> placed) {
        String date = LocalDate.now().toString();
        Direction[] faces = {Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST};
        for (Direction face : faces) {
            BlockPos sp = barrel.relative(face);
            // F11: sign spots get the same loaded-chunk / air / border /
            // protection validation as barrel spots — never force-load a chunk
            // or build inside someone else's claim for a cosmetic sign.
            if (!allowed(level, sp, skipProtected)) {
                continue;
            }
            BlockState state = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, face);
            level.setBlock(sp, state, 3);
            placed.add(new PlacedBlock(level, sp.immutable()));
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

    /** Air + inside border + already-loaded chunk + (F11) not inside an unverifiable claim. */
    private static boolean allowed(ServerLevel level, BlockPos pos, boolean skipProtected) {
        // F11: never synchronously load or generate chunks — only use loaded ones.
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        WorldBorder border = level.getWorldBorder();
        if (!border.isWithinBounds(pos.getX(), pos.getZ())) {
            return false;
        }
        return !skipProtected || !isProtected(level, pos);
    }

    /**
     * F11: detect Flan / FTB Chunks claims via reflection so we never modify
     * another player's protected land. Fail closed everywhere: when a mod is
     * present but its claim-query API isn't recognized, or when a recognized
     * check itself errors, the location is treated as protected and placement
     * is refused. Verify these hooks against the live server's mod versions.
     */
    private static boolean isProtected(ServerLevel level, BlockPos pos) {
        try {
            if (!flanChecked) {
                flanPresent = classExists("io.github.flemmli97.flan.api.ClaimHandler");
                flanChecked = true;
            }
            if (flanPresent && inFlanClaim(level, pos)) {
                return true;
            }
        } catch (Exception e) {
            LOG.warn("LoreCleaner Flan claim check failed; treating location as protected: {}", e.getMessage());
            return true;
        }
        try {
            if (!ftbChecked) {
                ftbPresent = classExists("dev.ftb.mods.ftbchunks.api.FTBChunksAPI")
                        || classExists("dev.ftb.mods.ftbchunks.FTBChunks");
                ftbChecked = true;
            }
            if (ftbPresent && inFtbClaim(level, pos)) {
                return true;
            }
        } catch (Exception e) {
            LOG.warn("LoreCleaner FTB Chunks claim check failed; treating location as protected: {}", e.getMessage());
            return true;
        }
        return false;
    }

    private static boolean inFlanClaim(ServerLevel level, BlockPos pos) throws Exception {
        Class<?> handler = Class.forName("io.github.flemmli97.flan.api.ClaimHandler");
        // Probe: static getClaimAt(BlockPos, ServerLevel) -> null means wilderness.
        Method probe = findStaticProbe(handler, "getClaimAt", BlockPos.class, ServerLevel.class);
        if (probe == null) {
            warnProtectionApiOnce("Flan", "getClaimAt(BlockPos, ServerLevel)");
            return true;
        }
        Object[] args = orderArgs(probe, pos, level);
        return probe.invoke(null, args) != null;
    }

    private static boolean inFtbClaim(ServerLevel level, BlockPos pos) throws Exception {
        // Probe a few candidate API shapes; FTB's API has shifted across versions.
        String[] candidates = {
                "dev.ftb.mods.ftbchunks.api.FTBChunksAPI",
                "dev.ftb.mods.ftbchunks.FTBChunks",
        };
        for (String cn : candidates) {
            Class<?> cls;
            try {
                cls = Class.forName(cn);
            } catch (ClassNotFoundException e) {
                continue;
            }
            Method probe = findStaticProbe(cls, "isChunkClaimed", ServerLevel.class, BlockPos.class);
            if (probe == null) {
                probe = findStaticProbe(cls, "getChunkClaim", ServerLevel.class, BlockPos.class);
            }
            if (probe != null) {
                Object[] args = orderArgs(probe, pos, level);
                Object r = probe.invoke(null, args);
                if (r instanceof Boolean b) {
                    return b;
                }
                return r != null;
            }
        }
        warnProtectionApiOnce("FTB Chunks", "isChunkClaimed/getChunkClaim");
        return true;
    }

    /** Find a static method by name whose params are (BlockPos, ServerLevel) in any order. */
    private static Method findStaticProbe(Class<?> cls, String name, Class<?> a, Class<?> b) {
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals(name) || !Modifier.isStatic(m.getModifiers())
                    || m.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if ((a.isAssignableFrom(p[0]) && b.isAssignableFrom(p[1]))
                    || (b.isAssignableFrom(p[0]) && a.isAssignableFrom(p[1]))) {
                return m;
            }
        }
        return null;
    }

    private static Object[] orderArgs(Method m, BlockPos pos, ServerLevel level) {
        Class<?>[] p = m.getParameterTypes();
        return new Object[] {
                BlockPos.class.isAssignableFrom(p[0]) ? pos : level,
                BlockPos.class.isAssignableFrom(p[1]) ? pos : level,
        };
    }

    private static void warnProtectionApiOnce(String mod, String expected) {
        if (!protectionApiWarned.add(mod)) {
            return;
        }
        LOG.warn("LoreCleaner: {} is installed but its claim-query API ({}) was not recognized — "
                + "treating every candidate spot as PROTECTED (fail closed) until the hook is "
                + "verified against the live mod version.",
                mod, expected);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
