package me.wesley1808.servercore;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.GenericBuilder;
import com.electronwill.nightconfig.toml.TomlFormat;
import net.minecraftforge.fml.loading.FMLPaths;
import org.goldenforge.GoldenForge;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.function.BiConsumer;

public class Config {
    @Nullable
    private static final GenericBuilder<CommentedConfig, CommentedFileConfig> CONFIG_BUILDER;
    private static boolean dirty = false;

    static {
        // Required to generate the config with the correct order.
        System.setProperty("nightconfig.preserveInsertionOrder", "true");

        // Initialize the config builder.
        GenericBuilder<CommentedConfig, CommentedFileConfig> builder;
        Path path = FMLPaths.GOLDENDIR.get().resolve("servercore.toml");
        try {
            builder = CommentedFileConfig.builder(path, TomlFormat.instance()).preserveInsertionOrder().sync();
        } catch (Throwable throwable) {
            GoldenForge.LOGGER.error("[ServerCore] Unable to initialize config builder: {}", throwable.getMessage());
            GoldenForge.LOGGER.error("[ServerCore] Load and save operations on the config file will not be available.");
            builder = null;
        }

        CONFIG_BUILDER = builder;
    }

    public static void load(boolean afterMixinLoad) {
        if (CONFIG_BUILDER != null) {
            try {
                CommentedFileConfig config = CONFIG_BUILDER.build();
                config.load();
                config.close();

                for (Table table : Table.values()) {
                    Config.validate(table, config);

                    if (afterMixinLoad || table.loadBeforeMixins) {
                        Config.loadEntries(config.get(table.key), table.clazz);
                    }
                }

                if (afterMixinLoad) {
                    Config.loadChanges();
                }
            } catch (Throwable throwable) {
                GoldenForge.LOGGER.error("[ServerCore] An error occurred whilst loading the config!", throwable);
            }
        }
    }

    public static void save() {
        Config.save(false);
    }

    public static void save(boolean force) {
        if (CONFIG_BUILDER != null && (dirty || force)) {
            try {
                CommentedFileConfig config = CONFIG_BUILDER.build();
                for (Table table : Table.values()) {
                    Config.validate(table, config);
                    Config.saveEntries(config.get(table.key), table.clazz);
                    config.setComment(table.key, table.comment);
                }

                config.save();
                config.close();
                dirty = false;
            } catch (Throwable throwable) {
                GoldenForge.LOGGER.error("[ServerCore] An error occurred whilst saving the config!", throwable);
            }
        }
    }

    public static boolean isConfigAvailable() {
        return CONFIG_BUILDER != null;
    }

    public static void setDirty() {
        dirty = true;
    }

    private static void loadChanges() {
        DynamicSetting.loadCustomOrder();
    }

    // Creates table when missing.
    private static void validate(Table table, CommentedFileConfig config) {
        if (!config.contains(table.key)) {
            config.add(table.key, CommentedConfig.inMemory());
        }
    }

    private static void loadEntries(CommentedConfig config, Class<?> clazz) throws IllegalAccessException {
        Config.forEachEntry(clazz, (field, entry) -> {
            final String key = field.getName().toLowerCase();
            final Object value = config.getOrElse(key, entry.getDefault());
            if (!entry.set(value)) {
                GoldenForge.LOGGER.error("[ServerCore] Invalid config entry found! {} = {} (Reverting back to default: {})", key, value, entry.getDefault());
            }
        });
    }

    private static void saveEntries(CommentedConfig config, Class<?> clazz) throws IllegalAccessException {
        config.clear();
        Config.forEachEntry(clazz, (field, entry) -> {
            final String key = field.getName().toLowerCase();
            final String comment = entry.getComment();
            config.set(key, entry.get());
            if (comment != null) {
                config.setComment(key, " " + comment.replace("\n", "\n "));
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> void forEachEntry(Class<?> clazz, BiConsumer<Field, ConfigEntry<T>> consumer) throws IllegalAccessException {
        for (Field field : clazz.getFields()) {
            if (field.get(clazz) instanceof ConfigEntry entry) {
                consumer.accept(field, entry);
            }
        }
    }

    public enum Table {
        DYNAMIC(DynamicConfig.class, false, "Modifies mobcaps, no-chunk-tick, simulation and view-distance depending on the MSPT.");

        public final String key;
        public final String comment;
        public final Class<?> clazz;
        public final boolean loadBeforeMixins;

        Table(Class<?> clazz, boolean loadBeforeMixins, String comment) {
            this.key = this.name().toLowerCase();
            this.comment = " " + comment.replace("\n", "\n ");
            this.clazz = clazz;
            this.loadBeforeMixins = loadBeforeMixins;
        }
    }
}