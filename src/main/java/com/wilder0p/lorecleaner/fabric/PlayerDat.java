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
import net.minecraft.world.level.Level;
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
            out.addAll(r.extracted);
            if (r.remaining == null || r.remaining.isEmpty()) {
                remove.add(i);
            } else {
                CompoundTag saved = encode(r.remaining, access);
                if (saved != null) {
                    list.set(i, saved);
                    dirty = true;
                } else {
                    remove.add(i);
                }
            }
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

    ServerLevel level(MinecraftServer server) {
        String dim = string("Dimension");
        if (dim != null && !dim.isBlank()) {
            Identifier id = Identifier.tryParse(dim);
            if (id != null) {
                for (ServerLevel level : server.getAllLevels()) {
                    if (level.dimension().identifier().equals(id)) {
                        return level;
                    }
                }
            }
        }
        return server.getLevel(Level.OVERWORLD);
    }

    void save() throws Exception {
        if (!dirty) {
            return;
        }
        File bak = new File(file.getAbsolutePath() + ".lorecleaner.bak");
        Files.copy(file.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
            NbtIo.writeCompressed(root, fos);
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        dirty = false;
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
