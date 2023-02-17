package org.goldenforge.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.versions.forge.ForgeVersion;
import org.goldenforge.GoldenForge;

public class GoldenForgeCommand {
    public GoldenForgeCommand(CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("goldenforge")
                .executes(GoldenForgeCommand::main));

    }


    private static int main(CommandContext<CommandSourceStack> ctx) {

        ctx.getSource().sendSystemMessage(Component.literal("This server is running " + GoldenForge.getBranding() + " by ModcraftMC")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\nPowered by Forge " + ForgeVersion.getVersion()).withStyle(ChatFormatting.GRAY)));
        return 0;

    }
}
