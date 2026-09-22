package com.wilder0p.lorecleaner.fabric;

import java.io.File;
import java.util.UUID;

public final class LogoutOwner {
    public enum Side { PAPER, FABRIC }

    private LogoutOwner() {}

    public static File paperDat(ModConfig cfg, UUID uuid) {
        return new File(cfg.paperPlayerDataDir, uuid + ".dat");
    }

    public static File fabricDat(ModConfig cfg, UUID uuid) {
        return new File(cfg.fabricPlayerDataDir, uuid + ".dat");
    }

    public static long mtime(File file) {
        return file != null && file.isFile() ? file.lastModified() : 0L;
    }

    public static long fabricMtime(ModConfig cfg, UUID uuid) {
        long m = mtime(fabricDat(cfg, uuid));
        if (m > 0 && m < cfg.fabricGoLiveEpochMs) {
            return 0L;
        }
        return m;
    }

    public static long paperMtime(ModConfig cfg, UUID uuid) {
        return mtime(paperDat(cfg, uuid));
    }

    public static long lastSeenMs(ModConfig cfg, UUID uuid) {
        return Math.max(paperMtime(cfg, uuid), fabricMtime(cfg, uuid));
    }

    public static Side owner(ModConfig cfg, UUID uuid) {
        return fabricMtime(cfg, uuid) > paperMtime(cfg, uuid) ? Side.FABRIC : Side.PAPER;
    }
}
