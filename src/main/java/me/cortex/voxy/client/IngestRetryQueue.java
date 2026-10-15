package me.cortex.voxy.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

//When a chunk is added or removed, the ingest of the chunk into the voxy world can fail
// because the client light engine has not received/computed the light data yet (light data
// lags behind the chunk packet). If the chunk is ingested never, the region keeps no lod
// data at all and renders as a permanent hole/white squares when it is unloaded and viewed
// from a distance.
//
//This queue retries the ingest on the client tick while the chunk is still loaded, closing
// that gap. Chunks that fail at removal time are not retryable (the chunk leaves the cache
// right after), but by then the light data has almost always arrived, and the data ingested
// at add time is what covers the region.
public class IngestRetryQueue {
    private static final int MAX_ATTEMPTS = 100;//~5 seconds at 20tps
    private static final long WARN_INTERVAL_MS = 10_000;

    private static final Long2IntOpenHashMap pending = new Long2IntOpenHashMap();//packed chunk pos -> attempts
    private static long lastWarnTime = 0;

    public static void onIngestFailure(int x, int z) {
        pending.put(ChunkPos.pack(x, z), 0);
    }

    public static void onIngestSuccess(int x, int z) {
        pending.remove(ChunkPos.pack(x, z));
    }

    public static void tick(ClientLevel level) {
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
                //Chunk is gone, nothing to retry (if it never got ingested while loaded it
                // will be re-ingested the next time it is loaded)
                it.remove();
                continue;
            }
            var res = VoxelIngestService.tryAutoIngestChunk(chunk);
            if (res == VoxelIngestService.IngestResult.INGESTED) {
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

    public static void clear() {
        pending.clear();
    }

    public static int size() {
        return pending.size();
    }
}
