package org.goldenforgelauncher;

public class GoldenForgeEntryPoint {

    public static boolean AUTOUPDATE = Boolean.getBoolean("goldenforge.autoupdate");
    public static boolean NO_UPDATE = Boolean.getBoolean("goldenforge.noupdate");

    public static void init() {
        System.out.println("   ___      _     _              ___                    ");
        System.out.println("  / _ \\___ | | __| | ___ _ __   / __\\__  _ __ __ _  ___ ");
        System.out.println(" / /_\\/ _ \\| |/ _` |/ _ \\ '_ \\ / _\\/ _ \\| '__/ _` |/ _ \\");
        System.out.println("/ /_\\\\ (_) | | (_| |  __/ | | / / | (_) | | | (_| |  __/");
        System.out.println("\\____/\\___/|_|\\__,_|\\___|_| |_\\/   \\___/|_|  \\__, |\\___|");
        System.out.println();
        System.out.println("         Your minecraft server is now optimized ! (THIS IS AN ALPHA BUILD DON'T USE IN PRODUCTION (unless you're crazy))");
        System.out.println("         Report issues here : https://github.com/GoldenForge/GoldenForge/issues");
        if (!NO_UPDATE) {
            if (!AUTOUPDATE)
                System.out.println("         To enable auto update, launch your server with -Dgoldenforge.autoupdate=true");
            System.out.println("");
            String currentVersion = getVersion();
            System.out.println("Current version : " + currentVersion);
        }
    }

    public static String getVersion() {
        return "DEV";
    }
}