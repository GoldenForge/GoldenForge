package io.papermc.paper.configuration;

import com.mojang.logging.*;
import io.papermc.paper.configuration.constraint.*;
import io.papermc.paper.configuration.type.number.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.*;
import net.minecraft.world.entity.*;
import org.goldenforge.*;
import org.slf4j.*;
import org.spongepowered.configurate.objectmapping.meta.*;

import java.util.*;

@SuppressWarnings({"CanBeFinal", "FieldCanBeLocal", "FieldMayBeFinal", "NotNullFieldNotInitialized", "InnerClassMayBeStatic"})
public class GlobalConfiguration extends ConfigurationPart {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int CURRENT_VERSION = 29; // (when you change the version, change the comment, so it conflicts on rebases): <insert changes here>
    private static GlobalConfiguration instance;
    public static boolean isFirstStart = false;
    public static GlobalConfiguration get() {
        return instance;
    }

    public PufferfishConfig pufferfishConfig;
    public class PufferfishConfig extends ConfigurationPart {
        public boolean enableAsyncMobSpawning = false;
        public String sentryDsn = "";

        @PostProcess
        private void postProcess() {
            if (sentryDsn != null && !sentryDsn.isBlank()) {
                //"gg.pufferfish.pufferfish.sentry.SentryManager.init(this);
            }
        }
    }

    public ChunkLoadingBasic chunkLoadingBasic;

    public class ChunkLoadingBasic extends ConfigurationPart {
        @Comment("The maximum rate in chunks per second that the server will send to any individual player. Set to -1 to disable this limit.")
        public double playerMaxChunkSendRate = 75.0;

        @Comment(
            "The maximum rate at which chunks will load for any individual player. " +
            "Note that this setting also affects chunk generations, since a chunk load is always first issued to test if a" +
            "chunk is already generated. Set to -1 to disable this limit."
        )
        public double playerMaxChunkLoadRate = 100.0;

        @Comment("The maximum rate at which chunks will generate for any individual player. Set to -1 to disable this limit.")
        public double playerMaxChunkGenerateRate = -1.0;
    }

    public ChunkLoadingAdvanced chunkLoadingAdvanced;

    public class ChunkLoadingAdvanced extends ConfigurationPart {
        @Comment(
            "Set to true if the server will match the chunk send radius that clients have configured" +
            "in their view distance settings if the client is less-than the server's send distance."
        )
        public boolean autoConfigSendDistance = true;

        @Comment(
            "Specifies the maximum amount of concurrent chunk loads that an individual player can have." +
            "Set to 0 to let the server configure it automatically per player, or set it to -1 to disable the limit."
        )
        public int playerMaxConcurrentChunkLoads = 0;

        @Comment(
            "Specifies the maximum amount of concurrent chunk generations that an individual player can have." +
            "Set to 0 to let the server configure it automatically per player, or set it to -1 to disable the limit."
        )
        public int playerMaxConcurrentChunkGenerates = 0;
    }
    static void set(GlobalConfiguration instance) {
        GlobalConfiguration.instance = instance;
    }

    @Setting(Configuration.VERSION_FIELD)
    public int version = CURRENT_VERSION;

    public Proxies proxies;

    public class Proxies extends ConfigurationPart {

        public Velocity velocity;

        public class Velocity extends ConfigurationPart {
            public boolean enabled = false;
            public boolean onlineMode = true;
            public boolean enableCrossStitch = false;
            public String secret = "";

            @PostProcess
            private void postProcess() {
                if (this.enabled && this.secret.isEmpty()) {
                    LOGGER.error("Velocity is enabled, but no secret key was specified. A secret key is required. Disabling velocity...");
                    this.enabled = false;
                }
            }
        }

        public boolean proxyProtocol = false;

        public boolean isProxyOnlineMode() {
            return MinecraftServer.getServer().usesAuthentication() || (this.velocity.enabled && this.velocity.onlineMode);
        }

        public boolean shouldEnableCrossStitch() {
            return this.velocity.enableCrossStitch;
        }
    }

    public Watchdog watchdog;

    public class Watchdog extends ConfigurationPart {
        public int timeoutTime = 60;
        public int earlyWarningEvery = 5000;
        public int earlyWarningDelay = 10000;
    }

    public UnsupportedSettings unsupportedSettings;

    public class UnsupportedSettings extends ConfigurationPart {
        @Comment("This setting controls if the broken behavior of disarmed tripwires not breaking should be allowed. This also allows for dupes")
        public boolean allowTripwireDisarmingExploits = false;
        @Comment("This setting allows for exploits related to end portals, for example sand duping")
        public boolean allowUnsafeEndPortalTeleportation = false;
        @Comment("This setting controls if players should be able to break bedrock, end portals and other intended to be permanent blocks.")
        public boolean allowPermanentBlockBreakExploits = false;
        @Comment("This setting controls if player should be able to use TNT duplication, but this also allows duplicating carpet, rails and potentially other items")
        public boolean allowPistonDuplication = false;
        public boolean performUsernameValidation = true;
        @Comment("This setting controls if players should be able to create headless pistons.")
        public boolean allowHeadlessPistons = false;
        @Comment("This setting controls if the vanilla damage tick should be skipped if damage was blocked via a shield.")
        public boolean skipVanillaDamageTickWhenShieldBlocked = false;
        @Comment("This setting controls what compression format is used for region files.")
        public CompressionFormat compressionFormat = CompressionFormat.ZLIB;

        public enum CompressionFormat {
            GZIP,
            ZLIB,
            LZ4,
            NONE
        }
    }

    public Commands commands;

    public class Commands extends ConfigurationPart {
        public boolean timeCommandAffectsAllWorlds = false;
    }

    public Logging logging;

    public class Logging extends ConfigurationPart {
        public boolean deobfuscateStacktraces = true;
    }

    @SuppressWarnings("unused") // used in postProcess
    public ChunkSystem chunkSystem;

    public class ChunkSystem extends ConfigurationPart {

        public int ioThreads = -1;
        public int workerThreads = -1;
        public boolean filterFluidPostProcessing = true;

        @PostProcess
        private void postProcess() {
            ca.spottedleaf.moonrise.common.util.MoonriseCommon.adjustWorkerThreads(this.workerThreads, this.ioThreads);
        }
    }

    public ItemValidation itemValidation;

    public class ItemValidation extends ConfigurationPart {
        public int displayName = 8192;
        public int loreLine = 8192;
        public Book book;

        public class Book extends ConfigurationPart {
            public int title = 8192;
            public int author = 8192;
            public int page = 16384;
        }

        public BookSize bookSize;

        public class BookSize extends ConfigurationPart {
            public int pageMax = 2560; // TODO this appears to be a duplicate setting with one above
            public double totalMultiplier = 0.98D; // TODO this should probably be merged into the above inner class
        }
        public boolean resolveSelectorsInBooks = false;
    }

    public Collisions collisions;

    public class Collisions extends ConfigurationPart {
        public boolean enablePlayerCollisions = true;
        public boolean sendFullPosForHardCollidingEntities = true;
    }

    public PlayerAutoSave playerAutoSave;


    public class PlayerAutoSave extends ConfigurationPart {
        public int rate = -1;
        private int maxPerTick = -1;
        public int maxPerTick() {
            if (this.maxPerTick < 0) {
                return (this.rate == 1 || this.rate > 100) ? 10 : 20;
            }
            return this.maxPerTick;
        }
    }

    public Misc misc;

    public class Misc extends ConfigurationPart {

        @SuppressWarnings("unused") // used in postProcess
        public ChatThreads chatThreads;
        public class ChatThreads extends ConfigurationPart {
            private int chatExecutorCoreSize = -1;
            private int chatExecutorMaxSize = -1;

            @PostProcess
            private void postProcess() {
                //noinspection ConstantConditions
                if (MinecraftServer.getServer() == null) return; // In testing env, this will be null here
                int _chatExecutorMaxSize = (this.chatExecutorMaxSize <= 0) ? Integer.MAX_VALUE : this.chatExecutorMaxSize; // This is somewhat dumb, but, this is the default, do we cap this?;
                int _chatExecutorCoreSize = Math.max(this.chatExecutorCoreSize, 0);

                if (_chatExecutorMaxSize < _chatExecutorCoreSize) {
                    _chatExecutorMaxSize = _chatExecutorCoreSize;
                }

                java.util.concurrent.ThreadPoolExecutor executor = (java.util.concurrent.ThreadPoolExecutor) MinecraftServer.getServer().chatExecutor;
                executor.setCorePoolSize(_chatExecutorCoreSize);
                executor.setMaximumPoolSize(_chatExecutorMaxSize);
            }
        }
        public int maxJoinsPerTick = 5;
        public boolean fixEntityPositionDesync = true;
        @Constraints.Min(4)
        public int regionFileCacheSize = 256;
        @Comment("See https://luckformula.emc.gs")
        public boolean useAlternativeLuckFormula = false;
        public IntOr.Default compressionLevel = IntOr.Default.USE_DEFAULT;
        @Comment("Defines the leniency distance added on the server to the interaction range of a player when validating interact packets.")
        public DoubleOr.Default clientInteractionLeniencyDistance = DoubleOr.Default.USE_DEFAULT;
        public double movedTooQuicklyMultiplier = 10.0D;
        public double movedWronglyThreshold;
    }

    public BlockUpdates blockUpdates;

    public class BlockUpdates extends ConfigurationPart {
        public boolean disableNoteblockUpdates = false;
        public boolean disableTripwireUpdates = false;
        public boolean disableChorusPlantUpdates = false;
        public boolean disableMushroomBlockUpdates = false;
        public boolean throttleWaterUpdates = false;
    }

//    public AsyncPathFinding asyncPathFinding;
//
//    public class AsyncPathFinding extends ConfigurationPart {
//        public boolean enabled = false;
//        public int asyncPathfindingMaxThreads = 0;
//        public int asyncPathfindingKeepalive = 60;
//        public int asyncPathfindingQueueSize = 0;
//        @Comment(" The policy to use when the queue is full and a new task is submitted.\n" +
//                "            FLUSH_ALL: All pending tasks will be run on server thread.\n" +
//                "            CALLER_RUNS: Newly submitted task will be run on server thread.")
//        public PathfindTaskRejectPolicy asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.FLUSH_ALL;
//
//        @PostProcess
//        public void onLoaded() {
//            final int availableProcessors = Runtime.getRuntime().availableProcessors();
//
//            if (asyncPathfindingMaxThreads < 0)
//                asyncPathfindingMaxThreads = Math.max(availableProcessors + asyncPathfindingMaxThreads, 1);
//            else if (asyncPathfindingMaxThreads == 0)
//                asyncPathfindingMaxThreads = Math.max(availableProcessors / 4, 1);
//            if (!enabled)
//                asyncPathfindingMaxThreads = 0;
//            else
//                GoldenForge.LOGGER.info("Using {} threads for Async Pathfinding", asyncPathfindingMaxThreads);
//
//            if (asyncPathfindingQueueSize <= 0)
//                asyncPathfindingQueueSize = asyncPathfindingMaxThreads * 256;
//        }
//    }

    public MultithreadedTracker multithreadedTracker;

    public class MultithreadedTracker extends ConfigurationPart {
        public boolean enabled = false;
        public boolean compatModeEnabled = false;
        public int asyncEntityTrackerMaxThreads = 0;
        public int asyncEntityTrackerKeepalive = 60;
        public int asyncEntityTrackerQueueSize = 0;

        @PostProcess
        public void onLoaded() {
            if (asyncEntityTrackerMaxThreads < 0)
                asyncEntityTrackerMaxThreads = Math.max(Runtime.getRuntime().availableProcessors() + asyncEntityTrackerMaxThreads, 1);
            else if (asyncEntityTrackerMaxThreads == 0)
                asyncEntityTrackerMaxThreads = Math.max(Runtime.getRuntime().availableProcessors() / 4, 1);

            if (!enabled)
                asyncEntityTrackerMaxThreads = 0;
            else
                GoldenForge.LOGGER.info("Using {} threads for Async Entity Tracker", asyncEntityTrackerMaxThreads);

            if (asyncEntityTrackerQueueSize <= 0)
                asyncEntityTrackerQueueSize = asyncEntityTrackerMaxThreads * 384;
        }
    }

    public AsyncChunkSend asyncChunkSend;

    public class AsyncChunkSend extends ConfigurationPart {
        public boolean enabled = false;
    }

    public AsyncTargetFinding asyncTargetFinding;

    public class AsyncTargetFinding extends ConfigurationPart {
        public boolean enabled = false;
        public boolean alertOther = true;
        public boolean searchBlock = true;
        public boolean searchEntity = true;
        public int queueSize = 4096;
        public long threshold = 10L;

        @PostProcess
        public void onLoaded() {
            if (queueSize <= 0) {
                queueSize = 4096;
            }
            if (threshold == 0L) {
                threshold = 10L;
            }
            if (!enabled) {
                alertOther = false;
                searchEntity = false;
                searchBlock = false;
            }
        }
    }

//    public DynamicActivationofBrain dynamicActivationofBrain;
//
//    public class DynamicActivationofBrain extends ConfigurationPart {
//        public boolean enabled = true;
//        public int startDistance = 12;
//        public int startDistanceSquared;
//        public int maximumActivationPrio = 20;
//        public int activationDistanceMod = 8;
//        public boolean dontEnableIfInWater = false;
//        public List<String> blackedEntities = new ArrayList<>();
//
//
//        @PostProcess
//        public void onLoaded() {
//            startDistanceSquared = startDistance * startDistance;
//
//            for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
//                entityType.dabEnabled = true; // reset all, before setting the ones to true
//            }
//
//            final String DEFAULT_PREFIX = ResourceLocation.DEFAULT_NAMESPACE + ResourceLocation.NAMESPACE_SEPARATOR;
//
//            for (String name : blackedEntities) {
//                // Be compatible with both `minecraft:example` and `example` syntax
//                // If unknown, show user config value in the logger instead of parsed result
//                String lowerName = name.toLowerCase(Locale.ROOT);
//                String typeId = lowerName.startsWith(DEFAULT_PREFIX) ? lowerName : DEFAULT_PREFIX + lowerName;
//
//                EntityType.byString(typeId).ifPresentOrElse(entityType ->
//                                entityType.dabEnabled = false,
//                        () -> GoldenForge.LOGGER.warn("Skip unknown entity {}, in {}", name +  ".blacklisted-entities")
//                );
//            }
//        }
//    }

    public LeafConfigs leafConfigs;

    public class LeafConfigs extends ConfigurationPart {
        public boolean reduceChunkSourceUpdates = true;
        public boolean reduceUselessEntityMovePackets = false;
        public boolean optimizePlayerMovementProcessing = true;
        public boolean throttleInactiveGoalSelectorTick = true;

        public OptimizeBiome optimizeBiome;

        public class OptimizeBiome extends ConfigurationPart {
            public boolean enabled = true;
            public boolean mobSpawn = true;
            public boolean advancement = true;
        }

        public BrainRunningBehaviorCacheUpdate brainRunningBehaviorCacheUpdate;

        public class BrainRunningBehaviorCacheUpdate extends ConfigurationPart {
            public int interval = 5;
        }

        @PostProcess
        public void onLoaded() {

        }
    }
}
