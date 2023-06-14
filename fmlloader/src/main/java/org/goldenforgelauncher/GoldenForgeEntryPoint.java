package org.goldenforgelauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.server.ServerMain;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class GoldenForgeEntryPoint {

    public static boolean AUTOUPDATE = Boolean.getBoolean("goldenforge.autoupdate");

    public static void init() {
        System.out.println("   ___      _     _              ___                    ");
        System.out.println("  / _ \\___ | | __| | ___ _ __   / __\\__  _ __ __ _  ___ ");
        System.out.println(" / /_\\/ _ \\| |/ _` |/ _ \\ '_ \\ / _\\/ _ \\| '__/ _` |/ _ \\");
        System.out.println("/ /_\\\\ (_) | | (_| |  __/ | | / / | (_) | | | (_| |  __/");
        System.out.println("\\____/\\___/|_|\\__,_|\\___|_| |_\\/   \\___/|_|  \\__, |\\___|");
        System.out.println();
        System.out.println("         Your minecraft server is now optimized ! (THIS IS AN ALPHA BUILD DON'T USE IN PRODUCTION (unless you're crazy))");
        System.out.println("         Report issues here : https://github.com/GoldenForge/GoldenForge/issues");
        if (!AUTOUPDATE)
            System.out.println("         To enable auto update, launch your server with -Dgoldenforge.autoupdate=true");
        System.out.println("");
        System.out.println("Checking for update...");
        String remoteVersion = getLatestVersion();
        String currentVersion = getVersion();
        System.out.println("Current version : " + currentVersion);
        System.out.println("Latest version : " + remoteVersion);
        if (!remoteVersion.equals(currentVersion)) {
                System.out.println("A new version of goldenforge is available ! https://github.com/GoldenForge/GoldenForge/releases");

                if (AUTOUPDATE) {
                    System.out.println("Downloading update");
                    download();
                    System.out.println("New version downloaded! relaunch the installer and start the server.");
                    System.exit(0);
                }
        }
    }

    private static void download() {
        try {
            JsonObject assetObj = makeRequestAndParse("https://api.github.com/repos/goldenforge/goldenforge/releases/latest", "assets").getAsJsonArray().get(0).getAsJsonObject();

            String assetUrl = assetObj.get("url").getAsString();

            String fileUrl = makeRequestAndParse(assetUrl, "browser_download_url").getAsString();
            String fileName = makeRequestAndParse(assetUrl, "name").getAsString();
            URL downloadUrl = new URL(fileUrl);

            try (InputStream in = downloadUrl.openStream()) {
                File installerFile = new File(fileName);
                if (installerFile.exists()) installerFile.delete();
                Files.copy(in, installerFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getLatestVersion() {
        return makeRequestAndParse("https://api.github.com/repos/goldenforge/goldenforge/releases/latest", "tag_name").getAsString();
    }

    private static JsonElement makeRequestAndParse(String url, String jsonObject) {
        try {
            URL api = new URL(url);
            URLConnection connection = api.openConnection();
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));

            StringBuilder response = new StringBuilder();
            String inputLine;

            while ((inputLine = in.readLine()) != null)
                response.append(inputLine);

            in.close();

            return parseJson(response.toString()).get(jsonObject);

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static JsonObject parseJson(String input) {
        JsonElement jelement = new JsonParser().parse(input);
        JsonObject jobject = jelement.getAsJsonObject();
        return jobject;
    }

    public static String getVersion() {
        return "alpha-0.0.7";
    }
}