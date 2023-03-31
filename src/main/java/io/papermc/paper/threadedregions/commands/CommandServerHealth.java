//package io.papermc.paper.threadedregions.commands;
//
//import io.papermc.paper.threadedregions.*;
//import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
//import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
//import net.kyori.adventure.text.Component;
//import net.kyori.adventure.text.TextComponent;
//import net.kyori.adventure.text.event.ClickEvent;
//import net.kyori.adventure.text.event.HoverEvent;
//import net.kyori.adventure.text.format.NamedTextColor;
//import net.kyori.adventure.text.format.TextColor;
//import net.kyori.adventure.text.format.TextDecoration;
//import net.minecraft.server.level.ServerLevel;
//import net.minecraft.world.level.ChunkPos;
//import org.bukkit.Bukkit;
//import org.bukkit.World;
//import org.bukkit.command.Command;
//import org.bukkit.command.CommandSender;
//import org.bukkit.craftbukkit.CraftWorld;
//import org.bukkit.entity.Entity;
//import org.bukkit.entity.Player;
//
//import java.text.DecimalFormat;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.List;
//import java.util.Locale;
//
//public final class CommandServerHealth extends Command {
//
//    private static final DecimalFormat TWO_DECIMAL_PLACES = new DecimalFormat("#0.00");
//    private static final DecimalFormat ONE_DECIMAL_PLACES = new DecimalFormat("#0.0");
//
//    private static final TextColor HEADER = TextColor.color(79, 164, 240);
//    private static final TextColor PRIMARY = TextColor.color(48, 145, 237);
//    private static final TextColor SECONDARY = TextColor.color(104, 177, 240);
//    private static final TextColor INFORMATION = TextColor.color(145, 198, 243);
//    private static final TextColor LIST = TextColor.color(33, 97, 188);
//
//    public CommandServerHealth() {
//        super("tps");
//        this.setUsage("/<command> [server/region] [lowest regions to display]");
//        this.setDescription("Reports information about server health.");
//        this.setPermission("bukkit.command.tps");
//    }
//
//    private static Component formatRegionInfo(final String prefix, final double util, final double mspt, final double tps,
//                                              final boolean newline) {
//        return Component.text()
//            .append(Component.text(prefix, PRIMARY, TextDecoration.BOLD))
//            .append(Component.text(ONE_DECIMAL_PLACES.format(util * 100.0), CommandUtil.getUtilisationColourRegion(util)))
//            .append(Component.text("% util at ", PRIMARY))
//            .append(Component.text(TWO_DECIMAL_PLACES.format(mspt), CommandUtil.getColourForMSPT(mspt)))
//            .append(Component.text(" MSPT at ", PRIMARY))
//            .append(Component.text(TWO_DECIMAL_PLACES.format(tps), CommandUtil.getColourForTPS(tps)))
//            .append(Component.text(" TPS" + (newline ? "\n" : ""), PRIMARY))
//            .build();
//    }
//
//    private static boolean executeRegion(final CommandSender sender, final String commandLabel, final String[] args) {
//        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
//            TickRegionScheduler.getCurrentRegion();
//        if (region == null) {
//            sender.sendMessage(Component.text("You are not in a region currently", NamedTextColor.RED));
//            return true;
//        }
//
//        final long currTime = System.nanoTime();
//
//        final TickData.TickReportData report15s = region.getData().getRegionSchedulingHandle().getTickReport15s(currTime);
//        final TickData.TickReportData report1m = region.getData().getRegionSchedulingHandle().getTickReport1m(currTime);
//
//        final ServerLevel world = region.regioniser.world;
//        final ChunkPos chunkCenter = region.getCenterChunk();
//        final int centerBlockX = ((chunkCenter.x << 4) | 7);
//        final int centerBlockZ = ((chunkCenter.z << 4) | 7);
//
//        final double util15s = report15s.utilisation();
//        final double tps15s = report15s.tpsData().segmentAll().average();
//        final double mspt15s = report15s.timePerTickData().segmentAll().average() / 1.0E6;
//
//        final double util1m = report1m.utilisation();
//        final double tps1m = report1m.tpsData().segmentAll().average();
//        final double mspt1m = report1m.timePerTickData().segmentAll().average() / 1.0E6;
//
//        final int yLoc = 80;
//        final String location = "[w:'" + world.getWorld().getName() + "'," + centerBlockX + "," + yLoc + "," + centerBlockZ + "]";
//
//        final Component line = Component.text()
//            .append(Component.text("Region around block ", PRIMARY))
//            .append(Component.text(location, INFORMATION))
//            .append(Component.text(":\n", PRIMARY))
//
//            .append(
//                formatRegionInfo("15s: ", util15s, mspt15s, tps15s, true)
//            )
//            .append(
//                formatRegionInfo("1m: ", util1m, mspt1m, tps1m, false)
//            )
//
//            .build();
//
//        sender.sendMessage(line);
//
//        return true;
//    }
//
//    private static boolean executeServer(final CommandSender sender, final String commandLabel, final String[] args) {
//        final int lowestRegionsCount;
//        if (args.length < 2) {
//            lowestRegionsCount = 3;
//        } else {
//            try {
//                lowestRegionsCount = Integer.parseInt(args[1]);
//            } catch (final NumberFormatException ex) {
//                sender.sendMessage(Component.text("Highest utilisation count '" + args[1] + "' must be an integer", NamedTextColor.RED));
//                return true;
//            }
//        }
//
//        final List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> regions =
//            new ArrayList<>();
//
//        for (final World bukkitWorld : Bukkit.getWorlds()) {
//            final ServerLevel world = ((CraftWorld)bukkitWorld).getHandle();
//            world.regioniser.computeForAllRegions(regions::add);
//        }
//
//        final double minTps;
//        final double medianTps;
//        final double maxTps;
//        double totalUtil = 0.0;
//
//        final DoubleArrayList tpsByRegion = new DoubleArrayList();
//        final List<TickData.TickReportData> reportsByRegion = new ArrayList<>();
//        final int maxThreadCount = TickRegions.getScheduler().getTotalThreadCount();
//
//        final long currTime = System.nanoTime();
//        final TickData.TickReportData globalTickReport = RegionizedServer.getGlobalTickData().getTickReport15s(currTime);
//
//        for (final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region : regions) {
//            final TickData.TickReportData report = region.getData().getRegionSchedulingHandle().getTickReport15s(currTime);
//            tpsByRegion.add(report == null ? 20.0 : report.tpsData().segmentAll().average());
//            reportsByRegion.add(report);
//            totalUtil += (report == null ? 0.0 : report.utilisation());
//        }
//
//        final double genRate = io.papermc.paper.chunk.system.scheduling.ChunkFullTask.genRate(currTime);
//        final double loadRate = io.papermc.paper.chunk.system.scheduling.ChunkFullTask.loadRate(currTime);
//
//        totalUtil += globalTickReport.utilisation();
//
//        tpsByRegion.sort(null);
//        if (!tpsByRegion.isEmpty()) {
//            minTps = tpsByRegion.getDouble(0);
//            maxTps = tpsByRegion.getDouble(tpsByRegion.size() - 1);
//
//            final int middle = tpsByRegion.size() >> 1;
//            if ((tpsByRegion.size() & 1) == 0) {
//                // even, average the two middle points
//                medianTps = (tpsByRegion.getDouble(middle - 1) + tpsByRegion.getDouble(middle)) / 2.0;
//            } else {
//                // odd, can just grab middle
//                medianTps = tpsByRegion.getDouble(middle);
//            }
//        } else {
//            // no regions = green
//            minTps = medianTps = maxTps = 20.0;
//        }
//
//        final List<ObjectObjectImmutablePair<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>, TickData.TickReportData>>
//            regionsBelowThreshold = new ArrayList<>();
//
//        for (int i = 0, len = regions.size(); i < len; ++i) {
//            final TickData.TickReportData report = reportsByRegion.get(i);
//
//            regionsBelowThreshold.add(new ObjectObjectImmutablePair<>(regions.get(i), report));
//        }
//
//        regionsBelowThreshold.sort((p1, p2) -> {
//            final TickData.TickReportData report1 = p1.right();
//            final TickData.TickReportData report2 = p2.right();
//            final double util1 = report1 == null ? 0.0 : report1.utilisation();
//            final double util2 = report2 == null ? 0.0 : report2.utilisation();
//
//            // we want the largest first
//            return Double.compare(util2, util1);
//        });
//
//        final TextComponent.Builder lowestRegionsBuilder = Component.text();
//
//        if (sender instanceof Player) {
//            lowestRegionsBuilder.append(Component.text(" Click to teleport\n", SECONDARY));
//        }
//        for (int i = 0, len = Math.min(lowestRegionsCount, regionsBelowThreshold.size()); i < len; ++i) {
//            final ObjectObjectImmutablePair<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>, TickData.TickReportData>
//                pair = regionsBelowThreshold.get(i);
//
//            final TickData.TickReportData report = pair.right();
//            final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
//                pair.left();
//
//            if (report == null) {
//                // skip regions with no data
//                continue;
//            }
//
//            final ServerLevel world = region.regioniser.world;
//            final ChunkPos chunkCenter = region.getCenterChunk();
//            if (chunkCenter == null) {
//                // region does not exist anymore
//                continue;
//            }
//            final int centerBlockX = ((chunkCenter.x << 4) | 7);
//            final int centerBlockZ = ((chunkCenter.z << 4) | 7);
//            final double util = report.utilisation();
//            final double tps = report.tpsData().segmentAll().average();
//            final double mspt = report.timePerTickData().segmentAll().average() / 1.0E6;
//
//            final int yLoc = 80;
//            final String location = "[w:'" + world.getWorld().getName() + "'," + centerBlockX + "," + yLoc + "," + centerBlockZ + "]";
//            final Component line = Component.text()
//                .append(Component.text(" - ", LIST, TextDecoration.BOLD))
//                .append(Component.text("Region around block ", PRIMARY))
//                .append(Component.text(location, INFORMATION))
//                .append(Component.text(":\n", PRIMARY))
//
//                .append(Component.text("    ", PRIMARY))
//                .append(Component.text(ONE_DECIMAL_PLACES.format(util * 100.0), CommandUtil.getUtilisationColourRegion(util)))
//                .append(Component.text("% util at ", PRIMARY))
//                .append(Component.text(TWO_DECIMAL_PLACES.format(mspt), CommandUtil.getColourForMSPT(mspt)))
//                .append(Component.text(" MSPT at ", PRIMARY))
//                .append(Component.text(TWO_DECIMAL_PLACES.format(tps), CommandUtil.getColourForTPS(tps)))
//                .append(Component.text(" TPS" + ((i + 1) == len ? "" : "\n"), PRIMARY))
//                .build()
//
//                .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.RUN_COMMAND, "/minecraft:execute as @s in " + world.getWorld().getKey().toString() + " run tp " + centerBlockX + ".5 " + yLoc + " " + centerBlockZ + ".5"))
//                .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT, Component.text("Click to teleport to " + location, SECONDARY)));
//
//            lowestRegionsBuilder.append(line);
//        }
//
//        sender.sendMessage(
//            Component.text()
//                .append(Component.text("Server Health Report\n", HEADER, TextDecoration.BOLD))
//
//                .append(Component.text(" - ", LIST, TextDecoration.BOLD))
//                .append(Component.text("Online Players: ", PRIMARY))
//                .append(Component.text(Bukkit.getOnlinePlayers().size() + "\n", INFORMATION))
//
//                .append(Component.text(" - ", LIST, TextDecoration.BOLD))
//                .append(Component.text("Total regions: ", PRIMARY))
//                .append(Component.text(regions.size() + "\n", INFORMATION))
//
//                .append(Component.text(" - ", LIST, TextDecoration.BOLD))
//                .append(Component.text("Utilisation: ", PRIMARY))
//                .append(Component.text(ONE_DECIMAL_PLACES.format(totalUtil * 100.0), CommandUtil.getUtilisationColourRegion(totalUtil / (double)maxThreadCount)))
//                .append(Component.text("% / ", PRIMARY))
//                .append(Component.text(ONE_DECIMAL_PLACES.format(maxThreadCount * 100.0), INFORMATION))
//                .append(Component.text("%\n", PRIMARY))
//
//                .append(Component.text(" - ", LIST, TextDecoration.BOLD))
//                .append(Component.text("Load rate: ", PRIMARY))
//                .append(Component.text(TWO_DECIMAL_PLACES.format(loadRate) + ", ", INFORMATION))
//                .append(Component.text("Gen rate: ", PRIMARY))
//                .append(Component.text(TWO_DECIMAL_PLACES.format(genRate) + "\n", INFORMATION))
//
//                .append(Component.text(" - ", LIST, TextDecoration.BOLD))
//                .append(Component.text("Lowest Region TPS: ", PRIMARY))
//                .append(Component.text(TWO_DECIMAL_PLACES.format(minTps) + "\n", CommandUtil.getColourForTPS(minTps)))
//
//
//                .append(Component.text(" - ", LIST, TextDecoration.BOLD))
//                .append(Component.text("Median Region TPS: ", PRIMARY))
//                .append(Component.text(TWO_DECIMAL_PLACES.format(medianTps) + "\n", CommandUtil.getColourForTPS(medianTps)))
//
//                .append(Component.text(" - ", LIST, TextDecoration.BOLD))
//                .append(Component.text("Highest Region TPS: ", PRIMARY))
//                .append(Component.text(TWO_DECIMAL_PLACES.format(maxTps) + "\n", CommandUtil.getColourForTPS(maxTps)))
//
//                .append(Component.text("Highest ", HEADER, TextDecoration.BOLD))
//                .append(Component.text(Integer.toString(lowestRegionsCount), INFORMATION, TextDecoration.BOLD))
//                .append(Component.text(" utilisation regions\n", HEADER, TextDecoration.BOLD))
//
//                .append(lowestRegionsBuilder.build())
//                .build()
//        );
//
//        return true;
//    }
//
//    @Override
//    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
//        final String type;
//        if (args.length < 1) {
//            type = "server";
//        } else {
//            type = args[0];
//        }
//
//        switch (type.toLowerCase(Locale.ROOT)) {
//            case "server": {
//                return executeServer(sender, commandLabel, args);
//            }
//            case "region": {
//                if (!(sender instanceof Entity)) {
//                    sender.sendMessage(Component.text("Cannot see current region information as console", NamedTextColor.RED));
//                    return true;
//                }
//                return executeRegion(sender, commandLabel, args);
//            }
//            default: {
//                sender.sendMessage(Component.text("Type '" + args[0] + "' must be one of: [server, region]", NamedTextColor.RED));
//                return true;
//            }
//        }
//    }
//
//    @Override
//    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) throws IllegalArgumentException {
//        if (args.length == 0) {
//            if (sender instanceof Entity) {
//                return CommandUtil.getSortedList(Arrays.asList("server", "region"));
//            } else {
//                return CommandUtil.getSortedList(Arrays.asList("server"));
//            }
//        } else if (args.length == 1) {
//            if (sender instanceof Entity) {
//                return CommandUtil.getSortedList(Arrays.asList("server", "region"), args[0]);
//            } else {
//                return CommandUtil.getSortedList(Arrays.asList("server"), args[0]);
//            }
//        }
//        return new ArrayList<>();
//    }
//}
