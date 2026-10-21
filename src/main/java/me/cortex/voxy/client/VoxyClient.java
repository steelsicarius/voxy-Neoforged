package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

//Client-only entrypoint of the mod (Fabric: ClientModInitializer).
//This class is never constructed on dedicated servers.
@Mod(value = VoxyCommon.MODID, dist = Dist.CLIENT)
public class VoxyClient {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;
    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
             Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            //Try acquire the lock file
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                vf.toFile().mkdir();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                //If some error write to log and unsupport
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }

        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        }
    }

    public VoxyClient(IEventBus modEventBus, ModContainer container) {
        //Fabric port: was ClientModInitializer.onInitializeClient().
        //The three DebugScreenEntries.register(...) calls from the old DebugEntries.init()
        //are now served by RegisterDebugEntriesEvent (fired on the mod event bus).
        modEventBus.addListener(DebugEntries::registerEntries);

        //Fabric port: was ClientCommandRegistrationCallback.EVENT.
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);

        //Retry ingests that failed because the light data was not available yet, see
        //IngestRetryQueue. Without this a chunk whose light lagged behind the chunk packet
        //never makes it into the voxy world and the region renders as a hole/white squares
        //when it is unloaded and viewed from a distance.
        NeoForge.EVENT_BUS.addListener(this::onClientTick);

        //Ingest the final state of a chunk right before it leaves the client chunk cache.
        //Fired from ClientChunkCache.drop, so the chunk is still fully readable here. This
        //was previously only done when bobby was installed, which left holes for chunks
        //whose add-time ingest had failed.
        NeoForge.EVENT_BUS.addListener(this::onChunkUnload);

        //Fabric port: ModMenu "getModConfigScreenFactory" (ModMenuIntegration) is now a
        //NeoForge config screen extension point. It opens the Sodium video settings
        //screen with the Voxy option page, exactly like the Fabric build did.
        container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, modListScreen) -> {
            if (VoxyCommon.isAvailable()) {
                var page = (OptionPage) ConfigManager.CONFIG.getModOptions().stream()
                        .filter(a -> a.configId().equals("voxy")).findFirst().get().pages().get(0);
                return VideoSettingsScreen.createScreen(modListScreen, page);
            }
            return null;
        });

        //Note (NeoForge port): the Fabric "frex_flawless_frames" entrypoint discovery was removed;
        //there is no entrypoint system on NeoForge, so FREX flawless frames integration stays
        //inactive and isFrexActive() always returns false unless explicitly activated.
    }

    void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        //Fabric port: was ClientCommandRegistrationCallback.EVENT.
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(VoxyCommands.register());
        }
    }

    void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (ClientSessionEvents.inSession && Minecraft.getInstance().level != null) {
            IngestRetryQueue.tick(Minecraft.getInstance().level);
        }
    }

    //Captures the final state of a chunk right before it leaves the client chunk cache
    //(fired from ClientChunkCache.drop where the chunk is still fully readable)
    void onChunkUnload(net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
        if (!ClientSessionEvents.inSession || !VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        var chunk = event.getChunk();
        if (!chunk.getLevel().isClientSide()) {
            return;//Ignore integrated server side chunks
        }
        //The renderer chunk-removed event usually ingests the chunk a moment earlier; skip
        // if that just happened so we dont voxelise the same chunk twice
        if (IngestRetryQueue.ingestedRecently(chunk.getPos().x(), chunk.getPos().z())) {
            return;
        }
        //allowNoLight: the final capture must never be lost to missing light data (the light
        //removal is queued separately, so the data is normally still there, but if it isnt a
        //dark section beats a hole)
        var res = VoxelIngestService.tryAutoIngestChunk(chunk, true);
        if (chunk.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel cl) {
            IngestRetryQueue.onIngestResult(cl, chunk.getPos().x(), chunk.getPos().z(), res);
        }
    }
    //Kept for source/behaviour compatibility with the Fabric build.
    @SuppressWarnings("unused")
    private static void frexHook(Consumer<Function<String, Consumer<Boolean>>> api) {
        api.accept(name->active->{if (active) {
            FREX.add(name);
        } else {
            FREX.remove(name);
        }});
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}
