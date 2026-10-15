package me.cortex.voxy.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.fml.ModList;

//Keeps the voxy world in sync with the chunks the vanilla client has loaded.
//
//Mechanisms:
// 1. Retry queue: when a chunk ingest fails because the client light engine has not
//    received/computed the light data yet (light data lags behind the chunk packet and is
//    processed at a bounded rate per tick), the chunk is re-attempted on every tick while
//    it is still loaded. Without this a chunk whose light lagged behind never makes it into
//    the voxy world, leaving a permanent hole when the chunk is unloaded and viewed from a
//    distance.
// 2. No-light fallback: if the light data does not arrive within MAX_ATTEMPTS (or the
//    caller asks for it), the chunk is ingested with zero light so the region at least has
//    correct geometry (a hole/white square is worse than a dark section). Chunks ingested
//    this way go into the lightRefresh set and are re-ingested with real light later.
//    The fallback is suppressed while an LSS (lod-server-support) server is actively
//    streaming terrain, because overwriting good server-provided columns with dark local
//    data would make things worse.
// 3. Sweep: every SWEEP_INTERVAL_TICKS all chunks loaded around the player are checked
//    against the set of already-ingested chunks; anything missing is ingested. This is the
//    reconciliation net that guarantees that everything around the player gets generated
//    and saved (even chunks that were loaded before the session/render system was ready, or
//    whose add/remove events were missed), so there are no empty chunks when moving away.
public class IngestRetryQueue {
    private static final int MAX_ATTEMPTS = 100;//~5 seconds at 20tps
    private static final int SWEEP_INTERVAL_TICKS = 60;
    private static final long WARN_INTERVAL_MS = 10_000;
    private static final long RECENT_INGEST_WINDOW_MS = 2000;

    private static final Long2IntOpenHashMap pending = new Long2IntOpenHashMap();//packed chunk pos -> attempts
    private static final java.util.Map<ResourceKey<Level>, LongSet> knownIngested = new java.util.HashMap<>();
    private static final java.util.Map<ResourceKey<Level>, LongSet> lightRefresh = new java.util.HashMap<>();
    private static final Long2LongOpenHashMap lastIngestAt = new Long2LongOpenHashMap();
    private static long lastWarnTime = 0;
    private static int ticksUntilSweep = SWEEP_INTERVAL_TICKS;

    private static boolean lssServerActive = false;
    private static long lastLssProbe = 0;

    private static LongSet knownSet(ClientLevel level) {
        return knownIngested.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet());
    }

    private static LongSet refreshSet(ClientLevel level) {
        return lightRefresh.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet());
    }

    //True while an LSS (lod-server-support) server is streaming terrain to this client:
    //in that case the server owns the distant terrain data and local no-light fallbacks
    //must not overwrite its (correctly lit) columns with dark ones.
    private static boolean lssServerActive() {
        long now = System.currentTimeMillis();
        if (now - lastLssProbe > 30_000) {
            lastLssProbe = now;
            boolean installed = ModList.get() == null || ModList.get().isLoaded("lss");
            if (installed) {
                Boolean enabled = null;
                try {
                    Class<?> api = Class.forName("dev.vox.lss.api.LSSApi");
                    enabled = (Boolean) api.getMethod("isServerEnabled").invoke(null);
                } catch (Throwable ignored) {
                }
                lssServerActive = Boolean.TRUE.equals(enabled);
            } else {
                lssServerActive = false;
            }
        }
        return lssServerActive;
    }

    public static void onIngestResult(ClientLevel level, int x, int z, VoxelIngestService.IngestResult result) {
        long pos = ChunkPos.pack(x, z);
        switch (result) {
            case INGESTED -> {
                knownSet(level).add(pos);
                refreshSet(level).remove(pos);
                pending.remove(pos);
                lastIngestAt.put(pos, System.currentTimeMillis());
            }
            case INGESTED_NO_LIGHT -> {
                knownSet(level).add(pos);
                refreshSet(level).add(pos);
                pending.remove(pos);
                lastIngestAt.put(pos, System.currentTimeMillis());
            }
            case NO_LIGHT -> {
                knownSet(level).remove(pos);
                pending.put(pos, 0);
            }
            case SKIPPED -> {}
        }
    }

    //True if the chunk was ingested very recently (avoids double ingest work when both the
    //renderer chunk-removed event and the ChunkEvent.Unload capture fire for the same chunk)
    public static boolean ingestedRecently(int x, int z) {
        long pos = ChunkPos.pack(x, z);
        return System.currentTimeMillis() - lastIngestAt.get(pos) < RECENT_INGEST_WINDOW_MS;
    }

    public static void tick(ClientLevel level) {
        tickPending(level);
        tickLightRefresh(level);
        if (--ticksUntilSweep <= 0) {
            ticksUntilSweep = SWEEP_INTERVAL_TICKS;
            sweep(level);
        }
    }

    private static void tickPending(ClientLevel level) {
        if (pending.isEmpty()) {
            return;
        }
        var it = pending.long2IntEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            long pos = entry.getLongKey();
            var cp = ChunkPos.unpack(pos);
            int x = cp.x();
            int z = cp.z();

            var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) {
                //Chunk is gone; the ChunkEvent.Unload hook already captured its final state
                // (with the no-light fallback if needed)
                it.remove();
                continue;
            }
            var res = VoxelIngestService.tryAutoIngestChunk(chunk);
            if (res == VoxelIngestService.IngestResult.INGESTED || res == VoxelIngestService.IngestResult.INGESTED_NO_LIGHT) {
                onIngestResult(level, x, z, res);
                it.remove();
                continue;
            }
            if (res != VoxelIngestService.IngestResult.NO_LIGHT) {
                //Non retryable reason (e.g. ingest disabled), drop it
                it.remove();
                continue;
            }
            //No light yet. If the LSS server is not streaming (it owns distant terrain),
            //fall back to a zero-light ingest so the region at least has geometry.
            if (!lssServerActive()) {
                var fb = VoxelIngestService.tryAutoIngestChunk(chunk, true);
                if (fb == VoxelIngestService.IngestResult.INGESTED_NO_LIGHT || fb == VoxelIngestService.IngestResult.INGESTED) {
                    onIngestResult(level, x, z, fb);
                    it.remove();
                    continue;
                }
            }
            int attempts = entry.getIntValue() + 1;
            if (attempts > MAX_ATTEMPTS) {
                if (System.currentTimeMillis()-lastWarnTime > WARN_INTERVAL_MS) {
                    lastWarnTime = System.currentTimeMillis();
                    Logger.warn("Chunk ("+x+", "+z+") could not be ingested after "+attempts+" attempts (light data never became available)");
                }
                it.remove();
                continue;
            }
            entry.setValue(attempts);
        }
    }

    //Re-ingests chunks that were stored without light, now that light may be available
    private static void tickLightRefresh(ClientLevel level) {
        var set = lightRefresh.get(level.dimension());
        if (set == null || set.isEmpty()) {
            return;
        }
        var it = set.iterator();
        while (it.hasNext()) {
            long pos = it.nextLong();
            var cp = ChunkPos.unpack(pos);
            var chunk = level.getChunkSource().getChunk(cp.x(), cp.z(), ChunkStatus.FULL, false);
            if (chunk == null) {
                it.remove();//Gone; the next visit ingests it fresh
                continue;
            }
            //Only worth retrying if the light actually arrived
            var res = VoxelIngestService.tryAutoIngestChunk(chunk);
            if (res == VoxelIngestService.IngestResult.INGESTED) {
                it.remove();
            } else if (res == VoxelIngestService.IngestResult.INGESTED_NO_LIGHT || res == VoxelIngestService.IngestResult.NO_LIGHT) {
                //Still no light, keep it in the refresh set (bounded work: one chunk per
                //sweep cycle is re-tried by the sweep anyway)
                if (set.size() > 256) {
                    it.remove();//Safety valve against unbounded growth
                }
            } else {
                it.remove();
            }
        }
    }

    //Ingests every loaded chunk around the player that the voxy world does not have yet
    private static void sweep(ClientLevel level) {
        if (!me.cortex.voxy.commonImpl.VoxyCommon.isAvailable()) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.options == null) {
            return;
        }
        int renderDistance = mc.options.renderDistance().get();
        var centerChunk = mc.player.chunkPosition();
        int pcx = centerChunk.x();
        int pcz = centerChunk.z();
        int radius = renderDistance + 1;

        var known = knownSet(level);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = pcx + dx;
                int z = pcz + dz;
                long pos = ChunkPos.pack(x, z);
                if (known.contains(pos)) {
                    continue;
                }
                var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                var res = VoxelIngestService.tryAutoIngestChunk(chunk);
                onIngestResult(level, x, z, res);
                if (res == VoxelIngestService.IngestResult.NO_LIGHT && !lssServerActive()) {
                    var fb = VoxelIngestService.tryAutoIngestChunk(chunk, true);
                    onIngestResult(level, x, z, fb);
                }
                //NO_LIGHT/SKIPPED otherwise: retried by the pending queue or the next sweep
            }
        }
    }

    public static void clear() {
        pending.clear();
        knownIngested.clear();
        lightRefresh.clear();
        lastIngestAt.clear();
        ticksUntilSweep = SWEEP_INTERVAL_TICKS;
    }

    public static int size() {
        return pending.size();
    }
}
