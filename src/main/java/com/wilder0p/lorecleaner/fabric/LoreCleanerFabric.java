package com.wilder0p.lorecleaner.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fabric-side inactive-player lore cleaner.
 *
 * Threading model (F7): the server tick thread only schedules. All NBT,
 * Redis and JDBC work runs on a single background thread; block placement
 * runs on the server thread via {@link #runOnServer(Callable)}.
 *
 * Safety model: Redis must be reachable (F5 fail closed), every mutation is
 * preceded by an immediate revalidation of online status, network presence,
 * logout ownership and inactivity (F9), barrel placement reports completeness
 * and anything not fully placed aborts before the .dat save (F1), and any
 * placement/save failure rolls the barrels back (F8).
 */
public final class LoreCleanerFabric implements ModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("LoreCleaner");

    /** Skip players whose Paper .dat was written very recently (cross-server write in flight). */
    private static final long PAPER_WRITE_QUIET_MS = 10 * 60 * 1000L;

    private ModConfig cfg;
    private RedisState redis;
    private PdsStore pds;
    private MinecraftServer server;
    private ExecutorService bg;
    private final ConcurrentLinkedQueue<UUID> queue = new ConcurrentLinkedQueue<>();
    private final java.util.Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private volatile long nextPlayerAtMs;
    private volatile long nextCycleAtMs;
    private volatile long lastRedisWarnMs;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onStart);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> shutdown());
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            UUID uuid = handler.getPlayer().getUUID();
            ExecutorService worker = bg;
            if (worker == null) {
                return;
            }
            // F7: Redis I/O must not run on the server thread. Check on the
            // background worker, then hop back to the server thread only to
            // send the chat message (and re-resolve the player in case they
            // already disconnected).
            worker.submit(() -> {
                boolean pending;
                try {
                    pending = redis != null && redis.hasPendingMessage(uuid);
                    if (pending) {
                        redis.clearPendingMessage(uuid);
                    }
                } catch (Exception e) {
                    LOG.warn("LoreCleaner login-message Redis check failed: {}", e.toString());
                    return;
                }
                if (pending) {
                    srv.execute(() -> {
                        net.minecraft.server.level.ServerPlayer p = srv.getPlayerList().getPlayer(uuid);
                        if (p != null) {
                            p.sendSystemMessage(Component.literal(cfg.cleanedOnLogin));
                        }
                    });
                }
            });
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
                Commands.literal("lorecleaner")
                        .then(Commands.literal("force").executes(ctx -> {
                            nextCycleAtMs = 0;
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("LoreCleaner full scan scheduled."), false);
                            return 1;
                        }))
                        .then(Commands.literal("status").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "LoreCleaner queue=" + queue.size() + " inFlight=" + inFlight.size()
                                            + " redis=" + (redis != null && redis.ready())
                                            + " bgShutdown=" + (bg == null || bg.isShutdown())), false);
                            return 1;
                        }))
        ));
        LOG.info("LoreCleaner Fabric 1.1.1 registered");
    }

    private void onStart(MinecraftServer server) {
        this.server = server;
        Path cfgDir = server.getServerDirectory().resolve("config");
        this.cfg = ModConfig.load(cfgDir.resolve("lorecleaner.json"));
        RedisState.Options opts = new RedisState.Options();
        opts.sentinelMaster = cfg.redisSentinelMaster;
        opts.sentinels = cfg.redisSentinels;
        opts.fallbackHost = cfg.redisFallbackHost;
        opts.fallbackPort = cfg.redisFallbackPort;
        opts.passwordFile = cfg.redisPasswordFile;
        opts.failClosed = cfg.redisFailClosed;
        this.redis = new RedisState(LOG, opts);
        try {
            this.redis.start();
        } catch (Exception e) {
            LOG.warn("LoreCleaner Redis start threw: {}", e.toString());
        }
        this.pds = new PdsStore(LOG);
        try {
            this.pds.start(cfgDir);
        } catch (Exception e) {
            LOG.warn("LoreCleaner PDS disabled: {}", e.toString());
        }
        // F4: startup self-test of the dual-decode slot path.
        try {
            PdsStore.selfTest(LOG, server.registryAccess());
        } catch (Exception e) {
            LOG.warn("LoreCleaner PDS self-test threw: {}", e.toString());
        }
        this.bg = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "lorecleaner-bg");
            t.setDaemon(true);
            return t;
        });
        long now = System.currentTimeMillis();
        this.nextPlayerAtMs = now;
        this.nextCycleAtMs = now;
        LOG.info("LoreCleaner Fabric 1.1.1 enabled (bg worker started)");
    }

    private void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        LOG.info("LoreCleaner shutting down bg worker...");
        if (bg != null) {
            bg.shutdown();
            try {
                if (!bg.awaitTermination(30, TimeUnit.SECONDS)) {
                    bg.shutdownNow();
                }
            } catch (InterruptedException e) {
                bg.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (redis != null) {
            redis.stop();
        }
    }

    /**
     * Server thread: scheduling only (F7). Throttles player jobs by
     * playersPerMinute and starts a new full scan every
     * cooldownAfterFullRunHours (F6).
     */
    private void tick(MinecraftServer srv) {
        if (cfg == null || !cfg.enabled || shuttingDown.get()) {
            return;
        }
        // F5: fail closed — without Redis we cannot verify online state,
        // ownership or cleaned watermarks, so the cycle does nothing at all.
        if (redis == null || !redis.ready()) {
            long now = System.currentTimeMillis();
            if (now - lastRedisWarnMs > 5 * 60 * 1000L) {
                lastRedisWarnMs = now;
                LOG.warn("LoreCleaner: Redis not ready — cleaning cycle paused (fail closed)");
            }
            return;
        }
        long now = System.currentTimeMillis();
        UUID next = queue.peek();
        if (next != null) {
            if (now >= nextPlayerAtMs && bg != null && !bg.isShutdown()) {
                queue.poll();
                nextPlayerAtMs = now + 60_000L / Math.max(1, cfg.playersPerMinute);
                inFlight.add(next);
                final UUID job = next;
                bg.submit(() -> {
                    try {
                        cleanPlayer(job);
                    } finally {
                        inFlight.remove(job);
                    }
                });
            }
            return;
        }
        // F6: queue drained — schedule the next full scan after the cooldown.
        if (now >= nextCycleAtMs && bg != null && !bg.isShutdown()) {
            nextCycleAtMs = now + cfg.cooldownAfterFullRunHours * 3600_000L;
            bg.submit(this::scanCycle);
        }
    }

    /** F6/F7: full playerdata scan on the bg thread; the tick loop drains the queue. */
    private void scanCycle() {
        if (shuttingDown.get()) {
            return;
        }
        try {
            int added = 0;
            File dir = new File(cfg.fabricPlayerDataDir);
            File[] files = dir.listFiles((d, n) -> n.endsWith(".dat") && !n.endsWith(".dat_old"));
            long now = System.currentTimeMillis();
            long inactiveMs = cfg.inactiveDays * 86400L * 1000L;
            long recheckMs = cfg.recheckDays * 86400L * 1000L;
            if (files != null) {
                for (File f : files) {
                    if (shuttingDown.get()) {
                        break;
                    }
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                    } catch (Exception e) {
                        continue;
                    }
                    if (inFlight.contains(uuid) || queue.contains(uuid)) {
                        continue;
                    }
                    if (LogoutOwner.owner(cfg, uuid) != LogoutOwner.Side.FABRIC) {
                        continue;
                    }
                    long seen = LogoutOwner.lastSeenMs(cfg, uuid);
                    if (now - seen < inactiveMs) {
                        continue;
                    }
                    // F5: if we cannot confirm cleaned/scanned state, don't queue.
                    if (!redis.ready()) {
                        continue;
                    }
                    var cleaned = redis.getCleaned(uuid);
                    if (cleaned != null && now - cleaned.toEpochMilli() < recheckMs) {
                        continue;
                    }
                    Long scanned = redis.getScanned(uuid);
                    if (scanned != null && scanned == seen) {
                        continue;
                    }
                    queue.offer(uuid);
                    added++;
                }
            }
            LOG.info("LoreCleaner scan cycle queued {} player(s); next cycle in {}h",
                    added, cfg.cooldownAfterFullRunHours);
        } catch (Exception e) {
            LOG.warn("LoreCleaner scan cycle failed: {}", e.toString());
        }
    }

    /** F7: all NBT/Redis/JDBC work for one player happens here on the bg thread. */
    private void cleanPlayer(UUID uuid) {
        if (shuttingDown.get()) {
            return;
        }
        try {
            // F9: revalidate immediately before doing anything.
            if (!preconditions(uuid)) {
                return;
            }
            File dat = LogoutOwner.fabricDat(cfg, uuid);
            if (!dat.isFile()) {
                return;
            }
            PlayerDat pdata = PlayerDat.load(dat, uuid);
            RegistryAccess access = server.registryAccess();
            // F3: snapshot pre-clean signatures BEFORE extraction mutates the NBT.
            Map<String, List<String>> signatures = pdata.canonicalSlotSignatures(access);
            List<ItemStack> lore = pdata.extractLore(access);
            long seen = LogoutOwner.lastSeenMs(cfg, uuid);
            if (pdata.conversionFailures) {
                // F16: undecodable players are marked scanned, not retried forever.
                LOG.warn("LoreCleaner skipping {}: item conversion failures; marked scanned", uuid);
                redis.setScanned(uuid, seen);
                return;
            }
            if (lore.isEmpty()) {
                redis.setScanned(uuid, seen);
                return;
            }
            // Block placement must run on the server thread (F7); bg waits.
            Placement placement = runOnServer(() -> placeBarrels(uuid, pdata, lore));
            if (placement == null || placement.result == null) {
                if (placement != null && placement.noPosition) {
                    // F19: logged once per player (marked scanned below).
                    LOG.warn("LoreCleaner {}: no logout position/dimension; marked scanned", uuid);
                    redis.setScanned(uuid, seen);
                }
                // else: player came online mid-run — leave unmarked for retry.
                return;
            }
            Barrels.PlacementResult pr = placement.result;
            // Rollback must run on the server thread (F7); the decision logic
            // itself is thread-agnostic and unit-testable.
            java.util.function.Consumer<Barrels.PlacementResult> rollback =
                    r -> runOnServer(() -> {
                        Barrels.rollbackPlacement(r);
                        return null;
                    });
            AfterPlacement decision =
                    finishCleaning(uuid, pr, pdata, access, signatures, seen, lore.size(), rollback);
            if (decision == AfterPlacement.PROCEED_SAVE) {
                DiscordWebhook.send(cfg.discordWebhookUrl, uuid.toString(), lore.size(), pr.barrelsPlaced);
                LOG.info("Cleaned {} — {} lore items in {} barrel(s)", uuid, lore.size(), pr.barrelsPlaced);
            }
        } catch (Exception e) {
            LOG.warn("LoreCleaner failed for {}: {}", uuid, e.toString());
        }
    }

    /** Post-placement decision. Package-visible for tests. */
    enum AfterPlacement {
        PROCEED_SAVE,
        ABORT_NO_AIR,
        ABORT_STALE,
        SAVE_FAILED,
    }

    /**
     * Runs after barrels were placed: F1 requires a complete placement before
     * the .dat save; F9 revalidates immediately before the save; F8 rolls the
     * barrels back via {@code rollback} whenever the .dat is not saved.
     * Returns the outcome.
     */
    AfterPlacement finishCleaning(UUID uuid, Barrels.PlacementResult pr, PlayerDat pdata,
                                  RegistryAccess access, Map<String, List<String>> signatures,
                                  long seen, int loreCount,
                                  java.util.function.Consumer<Barrels.PlacementResult> rollback) {
        if (pr == null || !pr.complete) {
            // F1: not everything fit — abort BEFORE the .dat save, roll back
            // what was placed, mark scanned (F16 no-air outcome).
            LOG.warn("LoreCleaner {}: only {}/{} lore items placed (no free air); "
                            + ".dat NOT saved; barrels rolled back; marked scanned",
                    uuid, pr == null ? 0 : pr.itemsPlaced, loreCount);
            rollback.accept(pr);
            redis.setScanned(uuid, seen);
            return AfterPlacement.ABORT_NO_AIR;
        }
        // F9: revalidate immediately before the .dat mutation.
        if (!preconditions(uuid)) {
            rollback.accept(pr);
            LOG.info("LoreCleaner {}: preconditions changed after placement; barrels rolled back", uuid);
            return AfterPlacement.ABORT_STALE;
        }
        try {
            pdata.save();
        } catch (Exception e) {
            // F8: .dat save failed — roll back the barrels so nothing is lost.
            rollback.accept(pr);
            LOG.error("LoreCleaner {}: .dat save failed ({}); barrels rolled back", uuid, e.toString());
            return AfterPlacement.SAVE_FAILED;
        }
        pds.strip(uuid, access, signatures);
        redis.setCleaned(uuid);
        redis.setScanned(uuid, seen);
        return AfterPlacement.PROCEED_SAVE;
    }

    /** Outcome of the server-thread placement step. */
    private static final class Placement {
        final Barrels.PlacementResult result;
        final boolean noPosition;

        Placement(Barrels.PlacementResult result, boolean noPosition) {
            this.result = result;
            this.noPosition = noPosition;
        }
    }

    /**
     * Runs on the server thread (F7): resolve the logout dimension/position,
     * find air and place barrels. Any exception mid-placement rolls back via
     * {@link Barrels#place} and propagates.
     */
    private Placement placeBarrels(UUID uuid, PlayerDat pdata, List<ItemStack> lore) {
        Vec3 pos = pdata.pos();
        ServerLevel level = pdata.level(server);
        if (pos == null || level == null) {
            return new Placement(null, true);
        }
        // F9/F19: the single online check, immediately before mutation.
        if (server.getPlayerList().getPlayer(uuid) != null) {
            return new Placement(null, false);
        }
        BlockPos origin = BlockPos.containing(pos.x, pos.y, pos.z);
        BlockPos place = Barrels.findAir(level, origin, cfg.skipProtectedRegions);
        if (place == null) {
            // F1: no air anywhere near logout — report incomplete so the caller
            // aborts before the .dat save.
            return new Placement(new Barrels.PlacementResult(0, 0, false, List.of()), false);
        }
        Barrels.PlacementResult result =
                Barrels.place(level, place, lore, uuid.toString(), cfg.skipProtectedRegions);
        return new Placement(result, false);
    }

    /**
     * F9: revalidate everything immediately before a mutation. Any failure —
     * or uncertainty — means "do not touch this player".
     */
    private boolean preconditions(UUID uuid) {
        if (onlineNow(uuid)) {
            return false;
        }
        // F5: Redis must be usable; without it nothing can be verified.
        if (redis == null || !redis.ready() || redis.isNetworkOnline(uuid)) {
            return false;
        }
        if (LogoutOwner.owner(cfg, uuid) != LogoutOwner.Side.FABRIC) {
            return false;
        }
        long now = System.currentTimeMillis();
        long inactiveMs = cfg.inactiveDays * 86400L * 1000L;
        if (now - LogoutOwner.lastSeenMs(cfg, uuid) < inactiveMs) {
            return false;
        }
        // Cross-server write in flight: Paper .dat touched very recently.
        if (LogoutOwner.paperMtime(cfg, uuid) > now - PAPER_WRITE_QUIET_MS) {
            return false;
        }
        return true;
    }

    /** F19: the one online check, executed on the server thread; fail closed on error. */
    private boolean onlineNow(UUID uuid) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(server.getPlayerList().getPlayer(uuid) != null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return true; // uncertain -> treat as online (fail closed)
        }
    }

    /** Run a task on the server thread and wait for its result (bg thread only). */
    private <T> T runOnServer(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("server-thread task failed", e);
        }
    }
}
