package com.wilder0p.lorecleaner.fabric;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ModConfig {
    public boolean enabled = true;
    public int inactiveDays = 180;
    public int playersPerMinute = 4;
    public int cooldownAfterFullRunHours = 72;
    public int recheckDays = 180;
    public String paperPlayerDataDir = "/mnt/pool/survival/world/players/data";
    public String fabricPlayerDataDir = "/mnt/pool/fabric/world/players/data";
    public long fabricGoLiveEpochMs = Instant.parse("2026-09-03T00:00:00Z").toEpochMilli();
    public String discordWebhookUrl = "";
    public String cleanedOnLogin = "While you were offline for more than 6 months, your lore items were moved into a barrel at your last logout location.";
    // F11: skip barrel placement inside protection claims we cannot verify.
    public boolean skipProtectedRegions = true;
    // F13: Redis connection settings (previously hardcoded in RedisState).
    public String redisSentinelMaster = "azpbmd";
    public List<String> redisSentinels = new ArrayList<>(List.of(
            "10.0.0.1:26379", "10.0.0.2:26379", "10.0.0.3:26379"));
    public String redisFallbackHost = "10.0.0.3";
    public int redisFallbackPort = 6379;
    public String redisPasswordFile = "/mnt/pool/skygate/redis.pass";
    public boolean redisFailClosed = true;

    public static ModConfig load(Path file) {
        ModConfig cfg = new ModConfig();
        try {
            if (!Files.isRegularFile(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(cfg.toJson()));
                return cfg;
            }
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (o.has("enabled")) cfg.enabled = o.get("enabled").getAsBoolean();
            if (o.has("inactiveDays")) cfg.inactiveDays = o.get("inactiveDays").getAsInt();
            if (o.has("playersPerMinute")) cfg.playersPerMinute = o.get("playersPerMinute").getAsInt();
            if (o.has("cooldownAfterFullRunHours")) cfg.cooldownAfterFullRunHours = o.get("cooldownAfterFullRunHours").getAsInt();
            if (o.has("recheckDays")) cfg.recheckDays = o.get("recheckDays").getAsInt();
            if (o.has("paperPlayerDataDir")) cfg.paperPlayerDataDir = o.get("paperPlayerDataDir").getAsString();
            if (o.has("fabricPlayerDataDir")) cfg.fabricPlayerDataDir = o.get("fabricPlayerDataDir").getAsString();
            if (o.has("fabricGoLive")) {
                cfg.fabricGoLiveEpochMs = Instant.parse(o.get("fabricGoLive").getAsString()).toEpochMilli();
            }
            if (o.has("discordWebhookUrl")) cfg.discordWebhookUrl = o.get("discordWebhookUrl").getAsString();
            if (o.has("cleanedOnLogin")) cfg.cleanedOnLogin = o.get("cleanedOnLogin").getAsString();
            if (o.has("skipProtectedRegions")) cfg.skipProtectedRegions = o.get("skipProtectedRegions").getAsBoolean();
            if (o.has("redisSentinelMaster")) cfg.redisSentinelMaster = o.get("redisSentinelMaster").getAsString();
            if (o.has("redisSentinels") && o.get("redisSentinels").isJsonArray()) {
                List<String> s = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("redisSentinels")) {
                    s.add(e.getAsString());
                }
                if (!s.isEmpty()) cfg.redisSentinels = s;
            }
            if (o.has("redisFallbackHost")) cfg.redisFallbackHost = o.get("redisFallbackHost").getAsString();
            if (o.has("redisFallbackPort")) cfg.redisFallbackPort = o.get("redisFallbackPort").getAsInt();
            if (o.has("redisPasswordFile")) cfg.redisPasswordFile = o.get("redisPasswordFile").getAsString();
            if (o.has("redisFailClosed")) cfg.redisFailClosed = o.get("redisFailClosed").getAsBoolean();
        } catch (Exception e) {
            System.getLogger("LoreCleaner").log(System.Logger.Level.WARNING, "config load failed: " + e.getMessage());
        }
        // F14: clamp minima so a bad config can't target everyone or spin forever.
        cfg.inactiveDays = Math.max(1, cfg.inactiveDays);
        cfg.playersPerMinute = Math.max(1, cfg.playersPerMinute);
        cfg.cooldownAfterFullRunHours = Math.max(1, cfg.cooldownAfterFullRunHours);
        cfg.recheckDays = Math.max(1, cfg.recheckDays);
        cfg.redisFallbackPort = Math.max(1, cfg.redisFallbackPort);
        return cfg;
    }

    private JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("inactiveDays", inactiveDays);
        o.addProperty("playersPerMinute", playersPerMinute);
        o.addProperty("cooldownAfterFullRunHours", cooldownAfterFullRunHours);
        o.addProperty("recheckDays", recheckDays);
        o.addProperty("paperPlayerDataDir", paperPlayerDataDir);
        o.addProperty("fabricPlayerDataDir", fabricPlayerDataDir);
        o.addProperty("fabricGoLive", "2026-09-03T00:00:00Z");
        o.addProperty("discordWebhookUrl", discordWebhookUrl);
        o.addProperty("cleanedOnLogin", cleanedOnLogin);
        o.addProperty("skipProtectedRegions", skipProtectedRegions);
        o.addProperty("redisSentinelMaster", redisSentinelMaster);
        JsonArray s = new JsonArray();
        for (String x : redisSentinels) s.add(x);
        o.add("redisSentinels", s);
        o.addProperty("redisFallbackHost", redisFallbackHost);
        o.addProperty("redisFallbackPort", redisFallbackPort);
        o.addProperty("redisPasswordFile", redisPasswordFile);
        o.addProperty("redisFailClosed", redisFailClosed);
        return o;
    }
}
