package com.wilder0p.lorecleaner.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

final class PdsStore {
    private final Logger log;
    private String jdbcUrl;
    private String user;
    private String password;
    private boolean ready;

    PdsStore(Logger log) {
        this.log = log;
    }

    void start(Path fabricConfigDir) {
        Path props = fabricConfigDir.resolve("playerdatasync.properties");
        if (!Files.isRegularFile(props)) {
            log.warn("PDS properties missing at {} — DB strip disabled", props);
            return;
        }
        try {
            Properties p = new Properties();
            try (var in = Files.newInputStream(props)) {
                p.load(in);
            }
            String host = p.getProperty("storage.host", "127.0.0.1");
            String port = p.getProperty("storage.port", "3306");
            String db = p.getProperty("storage.database", "playerdatasync");
            user = p.getProperty("storage.username", "");
            password = p.getProperty("storage.password", "");
            jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + db;
            Class.forName("org.mariadb.jdbc.Driver");
            try (Connection c = connect()) {
                c.createStatement().execute("SELECT 1");
            }
            ready = true;
            log.info("LoreCleaner PDS MariaDB ready");
        } catch (Exception e) {
            log.warn("LoreCleaner PDS MariaDB unavailable: {}", e.getMessage());
        }
    }

    /**
     * F4: decode one v2 slot's bytes with DUAL framing.
     * <p>
     * The Paper side (PlayerDataSync is a Bukkit plugin) writes Bukkit
     * {@code serializeAsBytes} framing — raw (uncompressed) NBT of the
     * CODEC-encoded compound — so that is tried first. Older rows may use
     * gzip-compressed NBT; that is the fallback. Anything else throws and the
     * caller must preserve the slot (never delete).
     */
    static ItemStack decodeSlot(byte[] bytes, RegistryAccess access) throws Exception {
        Exception rawEx = null;
        try {
            ItemStack s = parseFraming(bytes, false, access);
            if (s != null) {
                return s;
            }
        } catch (Exception e) {
            rawEx = e;
        }
        try {
            ItemStack s = parseFraming(bytes, true, access);
            if (s != null) {
                return s;
            }
        } catch (Exception e) {
            // fall through
        }
        throw new Exception("slot bytes match neither raw-NBT nor gzip-NBT framing", rawEx);
    }

    private static ItemStack parseFraming(byte[] bytes, boolean gzip, RegistryAccess access) {
        try {
            Tag tag;
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            if (gzip) {
                tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            } else {
                tag = NbtIo.read(new java.io.DataInputStream(in), NbtAccounter.unlimitedHeap());
            }
            if (!(tag instanceof CompoundTag compound)) {
                return null;
            }
            compound = compound.copy();
            compound.remove("DataVersion");
            return ItemStack.CODEC.parse(access.createSerializationContext(NbtOps.INSTANCE), compound).getOrThrow();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Encode one slot in Bukkit-compatible framing: raw (uncompressed) NBT of
     * the canonical CODEC compound, no DataVersion — the same bytes the Paper
     * side's {@code ItemStack.deserializeBytes} accepts. F4: rows written by
     * this side stay readable by the Paper side.
     */
    static byte[] encodeSlot(ItemStack stack, RegistryAccess access) {
        byte[] canon = PlayerDat.canonicalBytes(stack, access);
        if (canon == null) {
            throw new IllegalStateException("cannot canonical-encode slot");
        }
        return canon;
    }

    /**
     * F4 startup self-test: round-trip one slot through both framings and log
     * which format the decode path detects. Call once from onStart.
     */
    static void selfTest(Logger log, RegistryAccess access) {
        try {
            ItemStack probe = new ItemStack(net.minecraft.world.item.Items.STONE, 3);
            // Framing A: raw NBT (Bukkit layout).
            byte[] rawBytes = encodeSlot(probe, access);
            // Framing B: gzip NBT (legacy).
            CompoundTag compound = (CompoundTag) ItemStack.CODEC
                    .encodeStart(access.createSerializationContext(NbtOps.INSTANCE), probe).getOrThrow();
            java.io.ByteArrayOutputStream gz = new java.io.ByteArrayOutputStream();
            NbtIo.writeCompressed(compound, gz);
            byte[] gzipBytes = gz.toByteArray();

            ItemStack fromRaw = decodeSlot(rawBytes, access);
            ItemStack fromGzip = decodeSlot(gzipBytes, access);
            boolean ok = fromRaw.is(net.minecraft.world.item.Items.STONE) && fromRaw.getCount() == 3
                    && fromGzip.is(net.minecraft.world.item.Items.STONE) && fromGzip.getCount() == 3;
            log.info("LoreCleaner PDS slot-format self-test: raw-NBT {} / gzip-NBT {} — {}",
                    fromRaw.is(net.minecraft.world.item.Items.STONE) ? "OK" : "FAIL",
                    fromGzip.is(net.minecraft.world.item.Items.STONE) ? "OK" : "FAIL",
                    ok ? "dual-decode working" : "SELF-TEST FAILED");
        } catch (Exception e) {
            log.warn("LoreCleaner PDS slot-format self-test failed: {}", e.toString());
        }
    }

    /**
     * Strip lore from DB inventories.
     * <p>
     * F3: each v2 section is verified against {@code expectedSignatures} (the
     * pre-clean local snapshot from {@link PlayerDat#canonicalSlotSignatures})
     * before stripping. A mismatch means the DB row belongs to a different
     * session — the section is skipped and logged LOUDLY, never stripped.
     */
    void strip(UUID uuid, RegistryAccess access, Map<String, List<String>> expectedSignatures) {
        if (!ready || uuid == null) {
            return;
        }
        try (Connection c = connect();
             PreparedStatement sel = c.prepareStatement("SELECT data FROM player_data WHERE uuid = ?")) {
            sel.setString(1, uuid.toString());
            try (ResultSet rs = sel.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                String raw = rs.getString("data");
                if (raw == null || raw.isBlank()) {
                    return;
                }
                JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                boolean changed = false;
                changed |= stripSectionIfVerified(uuid, root, "inventoryContents", "inv", expectedSignatures, access);
                changed |= stripSectionIfVerified(uuid, root, "enderChestContents", "ec", expectedSignatures, access);
                if (!changed) {
                    return;
                }
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE player_data SET data = ?, last_updated = CURRENT_TIMESTAMP WHERE uuid = ?")) {
                    upd.setString(1, root.toString());
                    upd.setString(2, uuid.toString());
                    upd.executeUpdate();
                }
            }
        } catch (Exception e) {
            log.warn("PDS lore strip failed for {}: {}", uuid, e.getMessage());
        }
    }

    /**
     * F3: verify one DB section against the pre-clean local snapshot, then strip it.
     * Returns true when the section was modified. Any verification problem skips the
     * section loudly and never strips it.
     */
    private boolean stripSectionIfVerified(UUID uuid, JsonObject root, String field, String section,
                                           Map<String, List<String>> expectedSignatures, RegistryAccess access) {
        if (!root.has(field) || root.get(field).isJsonNull()) {
            return false;
        }
        String payload = root.get(field).getAsString();
        if (SlotDataFormat.kind(payload) != SlotDataFormat.Kind.V2) {
            log.warn("PDS inventory not v2 ({}); leaving DB row", SlotDataFormat.kind(payload));
            return false;
        }
        List<String> expected = expectedSignatures != null
                ? expectedSignatures.getOrDefault(section, List.of()) : List.of();
        List<String> actual = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> e : SlotDataFormat.decodeV2(payload).entrySet()) {
            final ItemStack stack;
            try {
                stack = decodeSlot(e.getValue(), access);
            } catch (Exception ex) {
                log.error("PDS STRIP SKIPPED for {} — DB {} slot {} undecodable; NOT stripping (fail closed)",
                        uuid, field, e.getKey());
                return false;
            }
            if (stack.isEmpty()) {
                continue;
            }
            byte[] canon = PlayerDat.canonicalBytes(stack, access);
            if (canon == null) {
                log.error("PDS STRIP SKIPPED for {} — DB {} slot {} not canonicalizable; NOT stripping (fail closed)",
                        uuid, field, e.getKey());
                return false;
            }
            actual.add(Base64.getEncoder().encodeToString(canon));
        }
        if (!multisetEquals(expected, actual)) {
            log.error("PDS STRIP SKIPPED for {} — DB {} does not match cleaned local state (db items={}, local items={}). "
                            + "The DB may belong to a different session; NOT stripping.",
                    uuid, field, actual.size(), expected.size());
            return false;
        }
        String next = stripPayload(payload, access);
        if (next != null && !next.equals(payload)) {
            root.addProperty(field, next);
            return true;
        }
        return false;
    }

    private static boolean multisetEquals(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String s : a) {
            counts.merge(s, 1, Integer::sum);
        }
        for (String s : b) {
            Integer n = counts.get(s);
            if (n == null || n == 0) {
                return false;
            }
            counts.put(s, n - 1);
        }
        return true;
    }

    private String stripPayload(String payload, RegistryAccess access) {
        SlotDataFormat.Kind kind = SlotDataFormat.kind(payload);
        if (kind != SlotDataFormat.Kind.V2) {
            log.warn("PDS inventory not v2 ({}); leaving DB row unchanged", kind);
            return payload;
        }
        Map<Integer, byte[]> slots = SlotDataFormat.decodeV2(payload);
        Map<Integer, byte[]> next = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<Integer, byte[]> e : slots.entrySet()) {
            ItemStack stack;
            try {
                stack = decodeSlot(e.getValue(), access);
            } catch (Exception ex) {
                // F4: undecodable slot — preserve original bytes, never delete.
                next.put(e.getKey(), e.getValue());
                continue;
            }
            LoreStacks.Result r = LoreStacks.extract(stack);
            if (r.extracted.isEmpty()) {
                next.put(e.getKey(), e.getValue());
                continue;
            }
            changed = true;
            if (r.remaining != null && !r.remaining.isEmpty()) {
                try {
                    next.put(e.getKey(), encodeSlot(r.remaining, access));
                } catch (Exception ex) {
                    // F12: preserve original slot bytes on encode failure; never
                    // abort the whole strip.
                    log.error("PDS slot {} re-encode failed; preserving original DB bytes", e.getKey());
                    next.put(e.getKey(), e.getValue());
                }
            }
            // else: slot fully extracted -> dropped from `next` (stripped).
        }
        return changed ? SlotDataFormat.encode(next) : payload;
    }

    private Connection connect() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", user == null ? "" : user);
        props.setProperty("password", password == null ? "" : password);
        return DriverManager.getConnection(jdbcUrl, props);
    }
}
