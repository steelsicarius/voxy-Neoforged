package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

//Provides access to chunks in the client chunk cache without the in-range check.
//The unload capture of chunks happens in VoxyClient via ChunkEvent.Unload (which
//ClientChunkCache.drop fires while the chunk is still fully readable).
@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache implements ICheekyClientChunkCache {

    @Shadow
    private volatile ClientChunkCache.Storage storage;

    @Override
    public @Nullable LevelChunk voxy$cheekyGetChunk(int x, int z) {
        //This doesnt do the in range check stuff, it just gets the chunk at all costs
        var chunk = this.storage.getChunk(this.storage.getIndex(x, z));
        if (chunk == null) {
            return null;
        }
        //Verify that the position of the chunk is the same as the requested position
        if (chunk.getPos().x() == x && chunk.getPos().z() == z) {
            return chunk;//The chunk is at the requested position
        }
        //Otherwise return null
        return null;
    }
}
