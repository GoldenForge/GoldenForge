package org.goldenforge.commands;

import com.google.common.collect.ImmutableMap;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraftforge.versions.forge.ForgeVersion;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.goldenforge.GoldenForge;
import org.goldenforge.tpsmonitor.TpsMonitorManager;

import java.util.Map;

public class GoldenForgeCommand {

    static final Map<MobCategory, TextColor> MOB_CATEGORY_COLORS = ImmutableMap.<MobCategory, TextColor>builder()
            .put(MobCategory.MONSTER, TextColor.fromLegacyFormat(ChatFormatting.RED))
            .put(MobCategory.CREATURE, TextColor.fromLegacyFormat(ChatFormatting.GREEN))
            .put(MobCategory.AMBIENT, TextColor.fromLegacyFormat(ChatFormatting.GRAY))
            .put(MobCategory.AXOLOTLS, TextColor.fromRgb(0x7324FF))
            .put(MobCategory.UNDERGROUND_WATER_CREATURE, TextColor.fromRgb(0x3541E6))
            .put(MobCategory.WATER_CREATURE, TextColor.fromRgb(0x006EFF))
            .put(MobCategory.WATER_AMBIENT, TextColor.fromRgb(0x00B3FF))
            .put(MobCategory.MISC, TextColor.fromRgb(0x636363))
            .build();


    public GoldenForgeCommand(CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("goldenforge")
                .executes(GoldenForgeCommand::main)
                .then(Commands.literal("chunkinfo").then(Commands.argument("world", DimensionArgument.dimension()).executes(GoldenForgeCommand::chunkInfos))));

        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("tpsmonitor")
                .executes(GoldenForgeCommand::toggleTPSMonitor));

        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("mobcaps").then(Commands.argument("world", DimensionArgument.dimension()))
                .executes(GoldenForgeCommand::printMobcaps));

        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("playermobcaps")
                .executes(GoldenForgeCommand::toggleTPSMonitor));

    }

    private static int printMobcaps(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        final ServerLevel level =  DimensionArgument.getDimension(ctx, "world");
        final NaturalSpawner.@Nullable SpawnState state = level.getChunkSource().getLastSpawnState();

        final int chunks;
        if (state == null) {
            chunks = 0;
        } else {
            chunks = state.getSpawnableChunkCount();
        }
//        ctx.getSource().sendSystemMessage(Component.empty().(JoinConfiguration.noSeparators(),
//                Component.literal("Mobcaps for world: "),
//                Component.literal(world.getName(), NamedTextColor.AQUA),
//                Component.literal(" (" + chunks + " spawnable chunks)")
//        ));
//
//        ctx.getSource().sendSystemMessage(createMobcapsComponent(
//                category -> {
//                    if (state == null) {
//                        return 0;
//                    } else {
//                        return state.getMobCategoryCounts().getOrDefault(category, 0);
//                    }
//                },
//                /*category -> NaturalSpawner.globalLimitForCategory(level, category, chunks)*/-1
//        ));

        return 0;
    }

//    private static Component createMobcapsComponent(final ToIntFunction<MobCategory> countGetter, final ToIntFunction<MobCategory> limitGetter) {
//        return MOB_CATEGORY_COLORS.entrySet().stream()
//                .map(entry -> {
//                    final MobCategory category = entry.getKey();
//                    final TextColor color = entry.getValue();
//
//                    final Component categoryHover = Component.join(JoinConfiguration.noSeparators(),
//                            Component.text("Entity types in category ", TextColor.color(0xE0E0E0)),
//                            Component.text(category.getName(), color),
//                            Component.text(':', NamedTextColor.GRAY),
//                            Component.newline(),
//                            Component.newline(),
//                            BuiltInRegistries.ENTITY_TYPE.entrySet().stream()
//                                    .filter(it -> it.getValue().getCategory() == category)
//                                    .map(it -> Component.translatable(it.getValue().getDescriptionId()))
//                                    .collect(Component.toComponent(Component.text(", ", NamedTextColor.GRAY)))
//                    );
//
//                    final Component categoryComponent = Component.text()
//                            .content("  " + category.getName())
//                            .color(color)
//                            .hoverEvent(categoryHover)
//                            .build();
//
//                    final TextComponent.Builder builder = Component.text()
//                            .append(
//                                    categoryComponent,
//                                    Component.text(": ", NamedTextColor.GRAY)
//                            );
//                    final int limit = limitGetter.applyAsInt(category);
//                    if (limit != -1) {
//                        builder.append(
//                                Component.text(countGetter.applyAsInt(category)),
//                                Component.text("/", NamedTextColor.GRAY),
//                                Component.text(limit)
//                        );
//                    } else {
//                        builder.append(Component.text()
//                                .append(
//                                        Component.text('n'),
//                                        Component.text("/", NamedTextColor.GRAY),
//                                        Component.text('a')
//                                )
//                                .hoverEvent(Component.text("This category does not naturally spawn.")));
//                    }
//                    return builder;
//                })
//                .map(ComponentLike::asComponent)
//                .collect(Component.toComponent(Component.newline()));
//    }


        private static int chunkInfos(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {

            CommandSourceStack sender = ctx.getSource();
            int accumulatedTotal = 0;
            int accumulatedInactive = 0;
            int accumulatedBorder = 0;
            int accumulatedTicking = 0;
            int accumulatedEntityTicking = 0;

            final ServerLevel world = DimensionArgument.getDimension(ctx, "world");

                int total = 0;
                int inactive = 0;
                int border = 0;
                int ticking = 0;
                int entityTicking = 0;

                for (final ChunkHolder chunk : io.papermc.paper.chunk.system.ChunkSystem.getVisibleChunkHolders(world)) {
                    if (chunk.getFullChunkNowUnchecked() == null) {
                        continue;
                    }

                    ++total;

                    ChunkHolder.FullChunkStatus state = chunk.getFullStatus();

                    switch (state) {
                        case INACCESSIBLE -> ++inactive;
                        case BORDER -> ++border;
                        case TICKING -> ++ticking;
                        case ENTITY_TICKING -> ++entityTicking;
                    }
                }

                accumulatedTotal += total;
                accumulatedInactive += inactive;
                accumulatedBorder += border;
                accumulatedTicking += ticking;
                accumulatedEntityTicking += entityTicking;

                sender.sendSystemMessage(Component.empty().append(Component.literal("Chunks in ").withStyle(ChatFormatting.BLUE)).append(Component.literal(world.dimension().location().toString()).withStyle(ChatFormatting.GREEN)).append(":"));

                sender.sendSystemMessage(Component.literal("Total: " + total).withStyle(ChatFormatting.BLUE));
                sender.sendSystemMessage(Component.literal("Inactive: " + inactive).withStyle(ChatFormatting.BLUE));
                sender.sendSystemMessage(Component.literal("Border: " + border).withStyle(ChatFormatting.BLUE));
                sender.sendSystemMessage(Component.literal("Ticking: " + ticking).withStyle(ChatFormatting.BLUE));
                sender.sendSystemMessage(Component.literal("Entity: " + entityTicking).withStyle(ChatFormatting.BLUE));
//        if (/*worlds.size() > 1*/ true) {
//            sender.sendSystemMessage(text().append(text("Chunks in ", BLUE), text("all listed worlds", GREEN), text(":", DARK_AQUA)));
//            sender.sendSystemMessage(text().color(DARK_AQUA).append(
//                    text("Total: ", BLUE), text(accumulatedTotal),
//                    text(" Inactive: ", BLUE), text(accumulatedInactive),
//                    text(" Border: ", BLUE), text(accumulatedBorder),
//                    text(" Ticking: ", BLUE), text(accumulatedTicking),
//                    text(" Entity: ", BLUE), text(accumulatedEntityTicking)
//            ));
//        }
        return 0;
    }

    private static int toggleTPSMonitor(CommandContext<CommandSourceStack> ctx) {

        if (ctx.getSource().getEntity() instanceof ServerPlayer serverPlayer) {
            TpsMonitorManager.get().togglePlayer(serverPlayer);
        }
        return 0;
    }


    private static int main(CommandContext<CommandSourceStack> ctx) {

        ctx.getSource().sendSystemMessage(Component.literal("This server is running " + GoldenForge.getBranding() + " by ModcraftMC")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\nPowered by Forge " + ForgeVersion.getVersion()).withStyle(ChatFormatting.GRAY)));
        return 0;

    }
}
