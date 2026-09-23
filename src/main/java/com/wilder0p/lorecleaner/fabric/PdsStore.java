package com.wilder0p.lorecleaner.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    void strip(UUID uuid, RegistryAccess access) {
        if (!ready) {
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
                for (String field : List.of("inventoryContents", "enderChestContents")) {
                    if (!root.has(field) || root.get(field).isJsonNull()) {
                        continue;
                    }
                    String payload = root.get(field).getAsString();
                    String next = stripPayload(payload, access);
                    if (next != null && !next.equals(payload)) {
                        root.addProperty(field, next);
                        changed = true;
                    }
                }
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

    private String stripPayload(String payload, RegistryAccess access) {
        SlotDataFormat.Kind kind = SlotDataFormat.kind(payload);
        if (kind != SlotDataFormat.Kind.V2) {
            log.warn("PDS inventory not v2 ({}); leaving DB row", kind);
            return payload;
        }
        Map<Integer, byte[]> slots = SlotDataFormat.decodeV2(payload);
        Map<Integer, byte[]> next = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<Integer, byte[]> e : slots.entrySet()) {
            ItemStack stack;
            try {
                stack = decodeBytes(e.getValue(), access);
            } catch (Exception ex) {
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
                next.put(e.getKey(), encodeBytes(r.remaining, access));
            }
        }
        return changed ? SlotDataFormat.encode(next) : payload;
    }

    private static ItemStack decodeBytes(byte[] bytes, RegistryAccess access) throws Exception {
        CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        tag.remove("DataVersion");
        return ItemStack.CODEC.parse(access.createSerializationContext(NbtOps.INSTANCE), tag).getOrThrow();
    }

    private static byte[] encodeBytes(ItemStack stack, RegistryAccess access) {
        try {
            Tag tag = ItemStack.CODEC.encodeStart(access.createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
            if (!(tag instanceof CompoundTag compound)) {
                throw new IllegalStateException("not compound");
            }
            compound.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(compound, out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Connection connect() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", user == null ? "" : user);
        props.setProperty("password", password == null ? "" : password);
        return DriverManager.getConnection(jdbcUrl, props);
    }
}
