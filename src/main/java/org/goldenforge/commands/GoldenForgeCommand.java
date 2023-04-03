package org.goldenforge.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.ResourceOrTagLocationArgument;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.versions.forge.ForgeVersion;
import org.bukkit.Bukkit;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.goldenforge.GoldenForge;
import org.goldenforge.tpsmonitor.TpsMonitorManager;

import java.util.ArrayList;
import java.util.List;

public class GoldenForgeCommand {
    public GoldenForgeCommand(CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("goldenforge")
                .executes(GoldenForgeCommand::main)
                .then(Commands.literal("chunkinfo").then(Commands.argument("world", DimensionArgument.dimension()).executes(GoldenForgeCommand::chunkInfos))));

        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("tpsmonitor")
                .executes(GoldenForgeCommand::toggleTPSMonitor));

    }

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
