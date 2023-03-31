//package io.papermc.paper.threadedregions.commands;
//
//import net.kyori.adventure.text.format.TextColor;
//import net.kyori.adventure.util.HSVLike;
//import net.minecraft.server.MinecraftServer;
//import net.minecraft.server.level.ServerPlayer;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.function.Function;
//
//public final class CommandUtil {
//
//    public static List<String> getSortedList(final Iterable<String> iterable) {
//        final List<String> ret = new ArrayList<>();
//        for (final String val : iterable) {
//            ret.add(val);
//        }
//
//        ret.sort(String.CASE_INSENSITIVE_ORDER);
//
//        return ret;
//    }
//
//    public static List<String> getSortedList(final Iterable<String> iterable, final String prefix) {
//        final List<String> ret = new ArrayList<>();
//        for (final String val : iterable) {
//            if (val.regionMatches(0, prefix, 0, prefix.length())) {
//                ret.add(val);
//            }
//        }
//
//        ret.sort(String.CASE_INSENSITIVE_ORDER);
//
//        return ret;
//    }
//
//    public static <T> List<String> getSortedList(final Iterable<T> iterable, final Function<T, String> transform) {
//        final List<String> ret = new ArrayList<>();
//        for (final T val : iterable) {
//            final String transformed = transform.apply(val);
//            if (transformed != null) {
//                ret.add(transformed);
//            }
//        }
//
//        ret.sort(String.CASE_INSENSITIVE_ORDER);
//
//        return ret;
//    }
//
//    public static <T> List<String> getSortedList(final Iterable<T> iterable, final Function<T, String> transform, final String prefix) {
//        final List<String> ret = new ArrayList<>();
//        for (final T val : iterable) {
//            final String string = transform.apply(val);
//            if (string != null && string.regionMatches(0, prefix, 0, prefix.length())) {
//                ret.add(string);
//            }
//        }
//
//        ret.sort(String.CASE_INSENSITIVE_ORDER);
//
//        return ret;
//    }
//
//    public static TextColor getColourForTPS(final double tps) {
//        final double difference = Math.min(Math.abs(20.0 - tps), 20.0);
//        final double coordinate;
//        if (difference <= 2.0) {
//            // >= 18 tps
//            coordinate = 70.0 + ((140.0 - 70.0)/(0.0 - 2.0)) * (difference - 2.0);
//        } else if (difference <= 5.0) {
//            // >= 15 tps
//            coordinate = 30.0 + ((70.0 - 30.0)/(2.0 - 5.0)) * (difference - 5.0);
//        } else if (difference <= 10.0) {
//            // >= 10 tps
//            coordinate = 10.0 + ((30.0 - 10.0)/(5.0 - 10.0)) * (difference - 10.0);
//        } else {
//            // >= 0.0 tps
//            coordinate = 0.0 + ((10.0 - 0.0)/(10.0 - 20.0)) * (difference - 20.0);
//        }
//
//        return TextColor.color(HSVLike.hsvLike((float)(coordinate / 360.0), 85.0f / 100.0f, 80.0f / 100.0f));
//    }
//
//    public static TextColor getColourForMSPT(final double mspt) {
//        final double clamped = Math.min(Math.abs(mspt), 50.0);
//        final double coordinate;
//        if (clamped <= 15.0) {
//            coordinate = 130.0 + ((140.0 - 130.0)/(0.0 - 15.0)) * (clamped - 15.0);
//        } else if (clamped <= 25.0) {
//            coordinate = 90.0 + ((130.0 - 90.0)/(15.0 - 25.0)) * (clamped - 25.0);
//        } else if (clamped <= 35.0) {
//            coordinate = 30.0 + ((90.0 - 30.0)/(25.0 - 35.0)) * (clamped - 35.0);
//        } else if (clamped <= 40.0) {
//            coordinate = 15.0 + ((30.0 - 15.0)/(35.0 - 40.0)) * (clamped - 40.0);
//        } else {
//            coordinate = 0.0 + ((15.0 - 0.0)/(40.0 - 50.0)) * (clamped - 50.0);
//        }
//
//        return TextColor.color(HSVLike.hsvLike((float)(coordinate / 360.0), 85.0f / 100.0f, 80.0f / 100.0f));
//    }
//
//    public static TextColor getUtilisationColourRegion(final double util) {
//        // TODO anything better?
//        // assume 20TPS
//        return getColourForMSPT(util * 50.0);
//    }
//
//    public static ServerPlayer getPlayer(final String name) {
//        for (final ServerPlayer player : MinecraftServer.getServer().getPlayerList().players) {
//            if (player.getGameProfile().getName().equalsIgnoreCase(name)) {
//                return player;
//            }
//        }
//
//        return null;
//    }
//
//    private CommandUtil() {}
//}
