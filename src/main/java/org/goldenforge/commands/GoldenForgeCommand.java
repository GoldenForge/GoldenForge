package org.goldenforge.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.versions.forge.ForgeVersion;
import org.goldenforge.GoldenForge;
import org.goldenforge.tpsmonitor.TpsMonitorManager;

public class GoldenForgeCommand {
    public GoldenForgeCommand(CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("goldenforge")
                .executes(GoldenForgeCommand::main));

        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("tpsmonitor")
                .executes(GoldenForgeCommand::toggleTPSMonitor));

    }

    private static int toggleTPSMonitor(CommandContext<CommandSourceStack> ctx) {

        if (ctx.getSource().getEntity() instanceof ServerPlayer) {
            TpsMonitorManager.get().togglePlayer((ServerPlayer) ctx.getSource().getEntity());
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
