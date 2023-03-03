package org.goldenforgelauncher;

import net.minecraftforge.fml.server.ServerMain;

public class GoldenForgeEntryPoint {

    public static void init() {
        System.out.println("   ___      _     _              ___                    ");
        System.out.println("  / _ \\___ | | __| | ___ _ __   / __\\__  _ __ __ _  ___ ");
        System.out.println(" / /_\\/ _ \\| |/ _` |/ _ \\ '_ \\ / _\\/ _ \\| '__/ _` |/ _ \\");
        System.out.println("/ /_\\\\ (_) | | (_| |  __/ | | / / | (_) | | | (_| |  __/");
        System.out.println("\\____/\\___/|_|\\__,_|\\___|_| |_\\/   \\___/|_|  \\__, |\\___|");
        System.out.println();
        System.out.println("         Your minecraft server is now optimized ! (THIS IS AN ALPHA BUILD DON'T USE IN PRODUCTION (unless you're crazy))");
        System.out.println("         Report issues here : https://github.com/GoldenForge/GoldenForge/issues ");
        System.out.println("");
//        System.out.println("Checking for update...");
//        String remoteVersion = getLatestVersion();
//        String currentVersion = getVersion();
//        System.out.println("Current version : " + currentVersion);
//        System.out.println("Latest version : " + remoteVersion);
//        if (!remoteVersion.equals(currentVersion))
//            System.out.println("A new version of goldenforge is available ! http://download.modcraftmc.fr:8080/job/GoldenForge/lastBuild/");

    }

    //TODO: updater using github actions.
//    private static String getLatestVersion() {
//        try {
//            URL website = new URL("http://download.modcraftmc.fr:8080/job/GoldenForge/lastBuild/api/json?tree=number");
//            URLConnection connection = website.openConnection();
//            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
//
//            StringBuilder response = new StringBuilder();
//            String inputLine;
//
//            while ((inputLine = in.readLine()) != null)
//                response.append(inputLine);
//
//            in.close();
//            JsonElement jelement = new JsonParser().parse(response.toString());
//            JsonObject jobject = jelement.getAsJsonObject();
//            return jobject.get("number").getAsString();
//
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//        return "dev";
//    }

    public static String getVersion() {
        return (ServerMain.class.getPackage().getImplementationVersion() != null) ? ServerMain.class.getPackage().getImplementationVersion() : "unknown";
    }
}