package com.wilder0p.lorecleaner.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

public final class LoreCleanerFabric implements ModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("LoreCleaner");

    private ModConfig cfg;
    private RedisState redis;
    private PdsStore pds;
    private MinecraftServer server;
    private final Queue<UUID> queue = new ArrayDeque<>();
    private boolean processing;
    private int tickCounter;
    private int delayTicks = 300;
    private Instant lastFullRun;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onStart);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (redis != null) {
                redis.stop();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID uuid = handler.getPlayer().getUUID();
            if (redis != null && redis.hasPendingMessage(uuid)) {
                handler.getPlayer().sendSystemMessage(Component.literal(cfg.cleanedOnLogin));
                redis.clearPendingMessage(uuid);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
                Commands.literal("lorecleaner")
                        .then(Commands.literal("force").executes(ctx -> {
                            fillQueue();
                            processing = true;
                            ctx.getSource().sendSuccess(() -> Component.literal("LoreCleaner force run queued."), false);
                            return 1;
                        }))
                        .then(Commands.literal("status").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "LoreCleaner fabric queue=" + queue.size() + " processing=" + processing
                                            + " redis=" + (redis != null && redis.ready())), false);
                            return 1;
                        }))
        ));
        LOG.info("LoreCleaner Fabric 1.1.1 registered");
    }

    private void onStart(MinecraftServer server) {
        this.server = server;
        Path cfgDir = server.getServerDirectory().resolve("config");
        this.cfg = ModConfig.load(cfgDir.resolve("lorecleaner.json"));
        this.redis = new RedisState(LOG);
        try {
            this.redis.start();
        } catch (Exception e) {
            LOG.warn("LoreCleaner Redis disabled: {}", e.toString());
        }
        this.pds = new PdsStore(LOG);
        try {
            this.pds.start(cfgDir);
        } catch (Exception e) {
            LOG.warn("LoreCleaner PDS disabled: {}", e.toString());
        }
        this.delayTicks = Math.max(1, 1200 / Math.max(1, cfg.playersPerMinute));
        if (cfg.enabled) {
            fillQueue();
            processing = !queue.isEmpty();
        }
        LOG.info("LoreCleaner Fabric 1.1.1 enabled, queued {}", queue.size());
    }

    private void tick(MinecraftServer server) {
        if (!processing || !cfg.enabled) {
            return;
        }
        tickCounter++;
        if (tickCounter < delayTicks) {
            return;
        }
        tickCounter = 0;
        UUID next = queue.poll();
        if (next == null) {
            processing = false;
            lastFullRun = Instant.now();
            LOG.info("LoreCleaner Fabric cycle done");
            return;
        }
        try {
            process(next);
        } catch (Exception e) {
            LOG.warn("LoreCleaner failed {}: {}", next, e.getMessage());
        }
        if (queue.isEmpty() && lastFullRun == null) {
            lastFullRun = Instant.now();
            processing = false;
        }
    }

    private void fillQueue() {
        queue.clear();
        File dir = new File(cfg.fabricPlayerDataDir);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".dat") && !n.endsWith(".dat_old"));
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long inactiveMs = cfg.inactiveDays * 86400L * 1000L;
        for (File f : files) {
            String name = f.getName();
            UUID uuid;
            try {
                uuid = UUID.fromString(name.substring(0, name.length() - 4));
            } catch (Exception e) {
                continue;
            }
            if (LogoutOwner.owner(cfg, uuid) != LogoutOwner.Side.FABRIC) {
                continue;
            }
            if (now - LogoutOwner.lastSeenMs(cfg, uuid) < inactiveMs) {
                continue;
            }
            Instant cleaned = redis.getCleaned(uuid);
            if (cleaned != null && now - cleaned.toEpochMilli() < cfg.recheckDays * 86400L * 1000L) {
                continue;
            }
            long seen = LogoutOwner.lastSeenMs(cfg, uuid);
            Long scanned = redis.getScanned(uuid);
            if (scanned != null && scanned == seen) {
                continue;
            }
            queue.add(uuid);
        }
    }

    private void process(UUID uuid) throws Exception {
        if (server.getPlayerList().getPlayer(uuid) != null) {
            return;
        }
        if (redis.isNetworkOnline(uuid)) {
            return;
        }
        if (LogoutOwner.owner(cfg, uuid) != LogoutOwner.Side.FABRIC) {
            return;
        }
        if (LogoutOwner.mtime(LogoutOwner.paperDat(cfg, uuid)) > System.currentTimeMillis() - 10 * 60 * 1000L) {
            return;
        }
        File dat = LogoutOwner.fabricDat(cfg, uuid);
        if (!dat.isFile()) {
            return;
        }
        PlayerDat pdata = PlayerDat.load(dat, uuid);
        var access = server.registryAccess();
        java.util.List<ItemStack> lore = pdata.extractLore(access);
        long seen = LogoutOwner.lastSeenMs(cfg, uuid);
        if (lore.isEmpty()) {
            if (!pdata.conversionFailures) {
                redis.setScanned(uuid, seen);
            }
            return;
        }
        Vec3 pos = pdata.pos();
        ServerLevel level = pdata.level(server);
        if (pos == null || level == null) {
            LOG.warn("No logout pos for {}", uuid);
            return;
        }
        BlockPos origin = BlockPos.containing(pos.x, pos.y, pos.z);
        BlockPos place = Barrels.findAir(level, origin);
        if (place == null) {
            LOG.warn("No air for barrels {}", uuid);
            return;
        }
        if (server.getPlayerList().getPlayer(uuid) != null) {
            return;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return;
        }
        String name = uuid.toString();
        int barrels = Barrels.place(level, place, lore, name);
        pdata.save();
        pds.strip(uuid, access);
        if (!pdata.conversionFailures) {
            redis.setCleaned(uuid);
            redis.setScanned(uuid, seen);
        } else {
            redis.setScanned(uuid, seen);
        }
        LOG.info("Cleaned {} — {} lore items in {} barrel(s)", uuid, lore.size(), barrels);
    }
}
