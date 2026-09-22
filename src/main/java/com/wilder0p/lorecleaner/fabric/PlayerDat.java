package com.wilder0p.lorecleaner.fabric;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class PlayerDat {
    final UUID uuid;
    final File file;
    CompoundTag root;
    boolean dirty;
    boolean conversionFailures;

    private PlayerDat(UUID uuid, File file, CompoundTag root) {
        this.uuid = uuid;
        this.file = file;
        this.root = root;
    }

    static PlayerDat load(File file, UUID uuid) throws Exception {
        CompoundTag root = NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap());
        return new PlayerDat(uuid, file, root);
    }

    List<ItemStack> extractLore(RegistryAccess access) {
        List<ItemStack> out = new ArrayList<>();
        out.addAll(extractList("Inventory", access));
        out.addAll(extractList("EnderItems", access));
        return out;
    }

    private List<ItemStack> extractList(String key, RegistryAccess access) {
        List<ItemStack> out = new ArrayList<>();
        ListTag list = list(key);
        if (list == null) {
            return out;
        }
        List<Integer> remove = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = compoundAt(list, i);
            if (itemTag == null) {
                continue;
            }
            ItemStack stack = decode(itemTag, access);
            if (stack == null) {
                conversionFailures = true;
                continue;
            }
            LoreStacks.Result r = LoreStacks.extract(stack);
            if (r.extracted.isEmpty()) {
                continue;
            }
            // F2: fully transactional per slot. Serialize the remainder FIRST;
            // only after encoding succeeds do we commit the slot mutation AND
            // queue the extracted lore. On encoding failure the original slot
            // bytes stay untouched and nothing is queued, so a dupe is
            // impossible (encode() already flagged conversionFailures).
            if (r.remaining != null && !r.remaining.isEmpty()) {
                CompoundTag saved = encode(r.remaining, access);
                if (saved == null) {
                    continue;
                }
                list.set(i, saved);
                dirty = true;
            } else {
                remove.add(i);
            }
            out.addAll(r.extracted);
        }
        for (int i = remove.size() - 1; i >= 0; i--) {
            list.remove(remove.get(i));
            dirty = true;
        }
        return out;
    }

    Vec3 pos() {
        ListTag list = list("Pos");
        if (list == null || list.size() < 3) {
            return null;
        }
        return new Vec3(list.getDouble(0).orElse(0.0), list.getDouble(1).orElse(0.0), list.getDouble(2).orElse(0.0));
    }

    /**
     * F10: fail closed — return null when the logout dimension cannot be
     * resolved instead of dropping nether/end coordinates into the overworld.
     */
    ServerLevel level(MinecraftServer server) {
        String dim = dimensionId();
        if (dim == null || dim.isBlank()) {
            return null;
        }
        Identifier id = Identifier.tryParse(dim);
        if (id == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().equals(id)) {
                return level;
            }
        }
        return null;
    }

    String dimensionId() {
        return string("Dimension");
    }

    void save() throws Exception {
        if (!dirty) {
            return;
        }
        // F15: timestamped backup generations (keep the last 5) instead of a
        // single file that gets overwritten on every save.
        String ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(java.time.LocalDateTime.now());
        Files.copy(file.toPath(),
                new File(file.getAbsolutePath() + ".lorecleaner.bak." + ts).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        pruneBackups();
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
            NbtIo.writeCompressed(root, fos);
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        dirty = false;
    }

    private void pruneBackups() {
        try {
            File dir = file.getParentFile();
            String prefix = file.getName() + ".lorecleaner.bak.";
            File[] baks = dir.listFiles((d, n) -> n.startsWith(prefix));
            if (baks == null || baks.length <= 5) {
                return;
            }
            java.util.Arrays.sort(baks, (a, b) -> a.getName().compareTo(b.getName()));
            for (int i = 0; i < baks.length - 5; i++) {
                try {
                    Files.deleteIfExists(baks[i].toPath());
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * F3: canonical per-section slot signatures of the PRE-CLEAN state, for the
     * PDS strip authority check. Each signature is base64 of the canonical
     * (CODEC-encoded, uncompressed NBT, no DataVersion) item bytes, so the DB
     * side can reproduce them exactly via dual-decode.
     */
    java.util.Map<String, java.util.List<String>> canonicalSlotSignatures(RegistryAccess access) {
        java.util.Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
        out.put("inv", signaturesFor(list("Inventory"), access));
        out.put("ec", signaturesFor(list("EnderItems"), access));
        return out;
    }

    private java.util.List<String> signaturesFor(ListTag list, RegistryAccess access) {
        java.util.List<String> sigs = new ArrayList<>();
        if (list == null) {
            return sigs;
        }
        for (int i = 0; i < list.size(); i++) {
            try {
                CompoundTag itemTag = compoundAt(list, i);
                if (itemTag == null) {
                    continue;
                }
                ItemStack stack = decode(itemTag, access);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                byte[] canon = canonicalBytes(stack, access);
                if (canon != null) {
                    sigs.add(java.util.Base64.getEncoder().encodeToString(canon));
                }
            } catch (Exception ignored) {
            }
        }
        return sigs;
    }

    /**
     * Canonical byte form of an item: CODEC-encoded, uncompressed NBT, with any
     * DataVersion tag stripped so .dat-side and DB-side signatures agree.
     */
    static byte[] canonicalBytes(ItemStack stack, RegistryAccess access) {
        try {
            Tag tag = ItemStack.CODEC.encodeStart(access.createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
            if (!(tag instanceof CompoundTag compound)) {
                return null;
            }
            compound.remove("DataVersion");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            NbtIo.write(compound, new java.io.DataOutputStream(out));
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private ListTag list(String key) {
        try {
            Object r = root.getClass().getMethod("getList", String.class).invoke(root, key);
            if (r instanceof Optional<?> opt) {
                Object v = opt.orElse(null);
                return v instanceof ListTag l ? l : null;
            }
            return r instanceof ListTag l ? l : null;
        } catch (Exception e) {
            try {
                Object r = root.getClass().getMethod("getList", String.class, int.class).invoke(root, key, 10);
                return r instanceof ListTag l ? l : null;
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private String string(String key) {
        try {
            Object r = root.getClass().getMethod("getString", String.class).invoke(root, key);
            if (r instanceof Optional<?> opt) {
                return opt.map(Object::toString).orElse(null);
            }
            return r != null ? r.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private CompoundTag compoundAt(ListTag list, int i) {
        try {
            Object r = list.getClass().getMethod("getCompound", int.class).invoke(list, i);
            if (r instanceof Optional<?> opt) {
                Object v = opt.orElse(null);
                return v instanceof CompoundTag c ? c : null;
            }
            return r instanceof CompoundTag c ? c : null;
        } catch (Exception e) {
            Tag t = list.get(i);
            return t instanceof CompoundTag c ? c : null;
        }
    }

    private ItemStack decode(CompoundTag tag, RegistryAccess access) {
        try {
            return ItemStack.CODEC.parse(access.createSerializationContext(NbtOps.INSTANCE), tag).getOrThrow();
        } catch (Exception e) {
            conversionFailures = true;
            return null;
        }
    }

    private CompoundTag encode(ItemStack stack, RegistryAccess access) {
        try {
            Tag tag = ItemStack.CODEC.encodeStart(access.createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
            return tag instanceof CompoundTag c ? c : null;
        } catch (Exception e) {
            conversionFailures = true;
            return null;
        }
    }
}
