package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(VoxyCommon.MODID)
public class VoxyCommon {
    public static final String MODID = "voxy";
    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;
    public static final boolean IS_IN_MINECRAFT;

    static {
        //Fabric port: the original resolved the mod container from FabricLoader.
        //On NeoForge we can only get here while FML is loading, but keep the
        //degraded "running without minecraft" path just in case (tools/tests).
        String modVersion = "<UNKNOWN>";
        boolean dedicated = false;
        boolean inMinecraft = false;
        try {
            var modInfo = ModList.get().getMods().stream()
                    .filter(info -> MODID.equals(info.getModId()))
                    .findFirst()
                    .orElse(null);
            inMinecraft = modInfo != null;
            if (inMinecraft) {
                modVersion = modInfo.getVersion().toString();
                dedicated = FMLEnvironment.getDist().isDedicatedServer();
            } else {
                Logger.error("Running voxy without minecraft");
            }
        } catch (Throwable t) {
            //FML not present or not initialized: assume we are outside of minecraft.
            inMinecraft = false;
            dedicated = false;
        }
        MOD_VERSION = modVersion;
        IS_DEDICATED_SERVER = dedicated;
        IS_IN_MINECRAFT = inMinecraft;
        if (IS_IN_MINECRAFT) {
            Serialization.init();
        }
    }

    public VoxyCommon() {
        //Common setup. On Fabric this was the (empty) ModInitializer.onInitialize().
    }

    //This is hardcoded like this because people do not understand what they are doing
    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return System.getProperty("voxy."+name, defaultOn?"true":"false").equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    public interface IInstanceFactory {VoxyInstance create();}
    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            INSTANCE = null;//Make it null before shutdown
            instance.shutdown();
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            //Logger.info("Voxy factory");
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        try {
            INSTANCE = FACTORY.create();
        } catch (DontCreateInstance e) {
            Logger.info("Not creating instance due to DontCreateInstance");
        }
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}
