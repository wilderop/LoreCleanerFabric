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
import java.util.Set;
import java.util.UUID;

/** Shared cleaned/scanned/pending-login keys plus BCH location for cross-server online. */
public final class RedisState {
    private final Logger logger;
    private Pool<Jedis> pool;

    public RedisState(Logger logger) {
        this.logger = logger;
    }

    public void start() {
        String password = "";
        try {
            Path pf = Path.of("redis.pass");
            if (Files.isRegularFile(pf)) {
                password = Files.readString(pf).trim();
            }
        } catch (Exception ignored) {
        }
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(4);
        Set<String> sentinels = Set.of("127.0.0.1:26379", "127.0.0.1:26379", "127.0.0.1:26379");
        try {
            pool = password.isBlank()
                    ? new JedisSentinelPool("azpbmd", sentinels, cfg, 3000)
                    : new JedisSentinelPool("azpbmd", sentinels, cfg, 3000, password);
            try (Jedis j = pool.getResource()) {
                j.ping();
            }
            logger.info("LoreCleaner Redis via Sentinel master=azpbmd");
        } catch (Exception e) {
            logger.warn("LoreCleaner Sentinel failed ({}), falling back to 127.0.0.1:6379", e.getMessage());
            if (pool != null) {
                try {
                    pool.close();
                } catch (Exception ignored) {
                }
            }
            pool = password.isBlank()
                    ? new JedisPool(cfg, "127.0.0.1", 6379, 3000)
                    : new JedisPool(cfg, "127.0.0.1", 6379, 3000, password);
            try (Jedis j = pool.getResource()) {
                j.ping();
            }
            logger.info("LoreCleaner Redis connected to 127.0.0.1:6379");
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

    public boolean isNetworkOnline(UUID uuid) {
        String loc = get("bch:loc:" + uuid);
        return loc != null && !loc.isBlank();
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
