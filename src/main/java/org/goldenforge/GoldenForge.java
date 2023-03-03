package org.goldenforge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GoldenForge {
    public static Logger LOGGER = LogManager.getLogger("GoldenForge");

    public static String VERSION = "ALPHA-1.0.0";

    public static String getBranding() {
        return "Goldenforge " + VERSION;
    }

}
