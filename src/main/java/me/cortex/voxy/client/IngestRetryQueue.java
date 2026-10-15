package me.cortex.voxy.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
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

//Keeps the voxy world in sync with the chunks the vanilla client has loaded.
//
//Two mechanisms:
// 1. Retry queue: when a chunk ingest fails because the client light engine has not
//    received/computed the light data yet (light data lags behind the chunk packet and is
//    processed at a bounded rate per tick), the chunk is re-attempted on every tick while
//    it is still loaded. Without this a chunk whose light lagged behind never makes it into
//    the voxy world, leaving a permanent hole when the chunk is unloaded and viewed from a
//    distance.
// 2. Sweep: every SWEEP_INTERVAL_TICKS all chunks loaded around the player are checked
//    against the set of already-ingested chunks; anything missing is ingested. This is the
//    reconciliation net that guarantees that everything around the player gets generated
//    and saved (even chunks that were loaded before the session/render system was ready, or
//    whose add/remove events were missed), so there are no empty chunks when moving away.
public class IngestRetryQueue {
    private static final int MAX_ATTEMPTS = 100;//~5 seconds at 20tps
    private static final int SWEEP_INTERVAL_TICKS = 60;
    private static final long WARN_INTERVAL_MS = 10_000;

    private static final Long2IntOpenHashMap pending = new Long2IntOpenHashMap();//packed chunk pos -> attempts
    private static final java.util.Map<ResourceKey<Level>, LongSet> knownIngested = new java.util.HashMap<>();
    private static long lastWarnTime = 0;
    private static int ticksUntilSweep = SWEEP_INTERVAL_TICKS;

    private static LongSet knownSet(ClientLevel level) {
        return knownIngested.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet());
    }

    public static void onIngestResult(ClientLevel level, int x, int z, VoxelIngestService.IngestResult result) {
        long pos = ChunkPos.pack(x, z);
        switch (result) {
            case INGESTED -> {
                knownSet(level).add(pos);
                pending.remove(pos);
            }
            case NO_LIGHT -> {
                knownSet(level).remove(pos);
                pending.put(pos, 0);
            }
            case SKIPPED -> {}
        }
    }

    public static void tick(ClientLevel level) {
        tickPending(level);
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
                //Chunk is gone, nothing to retry (the ChunkEvent.Unload hook ingests the
                // final state of a chunk right before it leaves the cache)
                it.remove();
                continue;
            }
            var res = VoxelIngestService.tryAutoIngestChunk(chunk);
            if (res == VoxelIngestService.IngestResult.INGESTED) {
                knownSet(level).add(pos);
                it.remove();
                continue;
            }
            if (res != VoxelIngestService.IngestResult.NO_LIGHT) {
                //Non retryable reason (e.g. ingest disabled), drop it
                it.remove();
                continue;
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
                if (res == VoxelIngestService.IngestResult.INGESTED) {
                    known.add(pos);
                }
                //NO_LIGHT/SKIPPED: retried by the pending queue (NO_LIGHT) or the next sweep
            }
        }
    }

    public static void clear() {
        pending.clear();
        knownIngested.clear();
        ticksUntilSweep = SWEEP_INTERVAL_TICKS;
    }

    public static int size() {
        return pending.size();
    }
}
