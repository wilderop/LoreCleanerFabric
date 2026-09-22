package com.wilder0p.lorecleaner.fabric;

import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.util.Pool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Shared cleaned/scanned/pending-login keys plus BCH location for cross-server online. */
public final class RedisState {

    /** F13: connection settings; defaults preserve the previous hardcoded values. */
    public static final class Options {
        public String sentinelMaster = "azpbmd";
        public java.util.List<String> sentinels = java.util.List.of(
                "10.0.0.1:26379", "10.0.0.2:26379", "10.0.0.3:26379");
        public String fallbackHost = "10.0.0.3";
        public int fallbackPort = 6379;
        public String passwordFile = "/mnt/pool/skygate/redis.pass";
        /**
         * F5: when true, an unreachable Redis means "treat as online" (skip the
         * player) instead of "safe to clean". Matches the Paper side.
         */
        public boolean failClosed = true;
    }

    private final Logger logger;
    private final Options opts;
    private Pool<Jedis> pool;

    public RedisState(Logger logger) {
        this(logger, new Options());
    }

    public RedisState(Logger logger, Options opts) {
        this.logger = logger;
        this.opts = opts != null ? opts : new Options();
    }

    public void start() {
        String password = "";
        try {
            Path pf = Path.of(opts.passwordFile);
            if (Files.isRegularFile(pf)) {
                password = Files.readString(pf).trim();
            }
        } catch (Exception ignored) {
        }
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(4);
        Set<String> sentinels = new HashSet<>(opts.sentinels);
        Pool<Jedis> sentinelPool = null;
        try {
            sentinelPool = password.isBlank()
                    ? new JedisSentinelPool(opts.sentinelMaster, sentinels, cfg, 3000)
                    : new JedisSentinelPool(opts.sentinelMaster, sentinels, cfg, 3000, password);
            try (Jedis j = sentinelPool.getResource()) {
                j.ping();
            }
            pool = sentinelPool;
            logger.info("LoreCleaner Redis via Sentinel master={}", opts.sentinelMaster);
        } catch (Exception e) {
            if (sentinelPool != null) {
                try {
                    sentinelPool.close();
                } catch (Exception ignored) {
                }
            }
            logger.warn("LoreCleaner Sentinel failed ({}), falling back to {}:{}",
                    e.getMessage(), opts.fallbackHost, opts.fallbackPort);
            // F5: never let a Redis outage kill startup or report healthy while
            // broken — degrade to pool == null instead.
            Pool<Jedis> direct = null;
            try {
                direct = password.isBlank()
                        ? new JedisPool(cfg, opts.fallbackHost, opts.fallbackPort, 3000)
                        : new JedisPool(cfg, opts.fallbackHost, opts.fallbackPort, 3000, password);
                try (Jedis j = direct.getResource()) {
                    j.ping();
                }
                pool = direct;
                logger.info("LoreCleaner Redis connected to {}:{}", opts.fallbackHost, opts.fallbackPort);
            } catch (Exception e2) {
                if (direct != null) {
                    try {
                        direct.close();
                    } catch (Exception ignored) {
                    }
                }
                pool = null;
                logger.error("LoreCleaner Redis unavailable ({}) — running degraded; players will be skipped, not cleaned",
                        e2.getMessage());
            }
        }
    }

    public void stop() {
        if (pool != null) {
            try {
                pool.close();
            } catch (Exception ignored) {
            }
            pool = null;
        }
    }

    public boolean ready() {
        return pool != null;
    }

    /**
     * True if BackChatHelper says they are on some network server.
     * F5: fail closed — when the online status cannot be determined, treat the
     * player as online so they are skipped instead of wrongly cleaned.
     */
    public boolean isNetworkOnline(UUID uuid) {
        if (uuid == null) {
            return opts.failClosed;
        }
        if (pool == null) {
            return opts.failClosed;
        }
        try (Jedis j = pool.getResource()) {
            String loc = j.get("bch:loc:" + uuid);
            return loc != null && !loc.isBlank();
        } catch (Exception e) {
            return opts.failClosed;
        }
    }

    public Long getScanned(UUID uuid) {
        return parseLong(get("lorecleaner:scanned:" + uuid));
    }

    public void setScanned(UUID uuid, long lastPlayed) {
        set("lorecleaner:scanned:" + uuid, Long.toString(lastPlayed));
    }

    public Instant getCleaned(UUID uuid) {
        Long ms = parseLong(get("lorecleaner:cleaned:" + uuid));
        return ms == null ? null : Instant.ofEpochMilli(ms);
    }

    public void setCleaned(UUID uuid) {
        long now = System.currentTimeMillis();
        set("lorecleaner:cleaned:" + uuid, Long.toString(now));
        set("lorecleaner:pendingmsg:" + uuid, "1");
    }

    public boolean hasPendingMessage(UUID uuid) {
        return "1".equals(get("lorecleaner:pendingmsg:" + uuid));
    }

    public void clearPendingMessage(UUID uuid) {
        del("lorecleaner:pendingmsg:" + uuid);
    }

    private String get(String key) {
        if (pool == null || key == null) {
            return null;
        }
        try (Jedis j = pool.getResource()) {
            return j.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private void set(String key, String value) {
        if (pool == null) {
            return;
        }
        try (Jedis j = pool.getResource()) {
            j.set(key, value);
        } catch (Exception e) {
            logger.warn("Redis SET {} failed: {}", key, e.getMessage());
        }
    }

    private void del(String key) {
        if (pool == null) {
            return;
        }
        try (Jedis j = pool.getResource()) {
            j.del(key);
        } catch (Exception ignored) {
        }
    }

    private static Long parseLong(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (Exception e) {
            return null;
        }
    }
}
