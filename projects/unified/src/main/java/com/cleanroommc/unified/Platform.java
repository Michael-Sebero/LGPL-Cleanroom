package com.cleanroommc.unified;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;

/**
 * Operating system / architecture detection and environment checks.
 */
final class Platform {

    enum OS {
        WINDOWS("windows"), MAC("mac"), LINUX("linux");

        /** Name used by the Adoptium API. */
        final String adoptium;

        OS(String adoptium) {
            this.adoptium = adoptium;
        }
    }

    static final OS OS_TYPE = detectOs();
    /** Adoptium-style architecture name: x64, aarch64, x86, arm. */
    static final String ARCH = detectArch();

    private Platform() {
    }

    private static OS detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return OS.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin") || name.contains("osx")) {
            return OS.MAC;
        }
        return OS.LINUX;
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        switch (arch) {
            case "amd64":
            case "x86_64":
            case "x64":
                return "x64";
            case "aarch64":
            case "arm64":
                return "aarch64";
            case "x86":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
                return "x86";
            default:
                return arch.startsWith("arm") ? "arm" : arch;
        }
    }

    /**
     * The LWJGL natives classifier matching this machine, or {@code null} if LWJGL has none.
     */
    static String lwjglNativesClassifier() {
        switch (OS_TYPE) {
            case WINDOWS:
                return "aarch64".equals(ARCH) ? "natives-windows-arm64" : "x86".equals(ARCH) ? "natives-windows-x86" : "natives-windows";
            case MAC:
                return "aarch64".equals(ARCH) ? "natives-macos-arm64" : "natives-macos";
            default:
                return "aarch64".equals(ARCH) ? "natives-linux-arm64" : "arm".equals(ARCH) ? "natives-linux-arm32" : "natives-linux";
        }
    }

    /**
     * True when the current process was launched by Cleanroom (Foundation) rather than by Forge's LaunchWrapper.
     */
    static boolean isCleanroom() {
        ClassLoader loader = Platform.class.getClassLoader();
        return loader.getResource("top/outlands/foundation/boot/Foundation.class") != null
                || loader.getResource("com/cleanroommc/common/CleanroomVersion.class") != null;
    }

    static File gameDir() {
        File home = net.minecraft.launchwrapper.Launch.minecraftHome;
        return (home != null ? home : new File(".")).getAbsoluteFile();
    }

    /**
     * The local file behind a {@code file:} URL, or the jar file behind a {@code jar:file:/x.jar!/entry} URL.
     * Entries never contain "!/", so the last one separates the jar from the entry even if a folder name ends in "!".
     */
    static File fileOf(URL url) throws IOException {
        String spec = url.toString();
        if (spec.startsWith("jar:")) {
            int separator = spec.lastIndexOf("!/");
            spec = spec.substring(4, separator < 0 ? spec.length() : separator);
        }
        if (!spec.startsWith("file:")) {
            throw new IOException("Not a local file: " + url);
        }
        try {
            return new File(new URI(spec));
        } catch (URISyntaxException | IllegalArgumentException e) {
            // Not properly escaped (e.g. raw spaces): decode the path by hand
            return new File(URLDecoder.decode(new URL(spec).getPath(), "UTF-8"));
        }
    }

    static String executableName(String base) {
        return OS_TYPE == OS.WINDOWS ? base + ".exe" : base;
    }
}
