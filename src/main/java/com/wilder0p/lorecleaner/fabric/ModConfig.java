package com.wilder0p.lorecleaner.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

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
        } catch (Exception e) {
            System.getLogger("LoreCleaner").log(System.Logger.Level.WARNING, "config load failed: " + e.getMessage());
        }
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
        return o;
    }
}
