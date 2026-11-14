package com.cleanroommc.unified;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * {@code config/cleanroom-unified.properties}. Written with defaults on first launch.
 */
final class UnifiedConfig {

    static final String FILE_NAME = "cleanroom-unified.properties";
    static final int DEFAULT_JAVA_VERSION = 25;

    /** Path to a java executable or Java home. Empty = use the managed runtime. */
    final String javaPath;
    /** Feature version of the managed runtime to download. */
    final int managedJavaVersion;
    /** Extra JVM arguments for the relaunched game, split on whitespace. */
    final String extraJvmArgs;

    private UnifiedConfig(Properties p) {
        this.javaPath = p.getProperty("javaPath", "").trim();
        this.extraJvmArgs = p.getProperty("extraJvmArgs", "").trim();
        int version;
        try {
            version = Integer.parseInt(p.getProperty("managedJavaVersion", String.valueOf(DEFAULT_JAVA_VERSION)).trim());
        } catch (NumberFormatException e) {
            version = DEFAULT_JAVA_VERSION;
        }
        this.managedJavaVersion = Math.max(DEFAULT_JAVA_VERSION, version);
    }

    static UnifiedConfig load(File gameDir) {
        File file = new File(new File(gameDir, "config"), FILE_NAME);
        Properties p = new Properties();
        if (file.isFile()) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                p.load(in);
            } catch (IOException e) {
                UnifiedLog.error("Could not read " + file + ", using defaults", e);
            }
        } else {
            writeDefaults(file);
        }
        return new UnifiedConfig(p);
    }

    private static void writeDefaults(File file) {
        try {
            Files.createDirectories(file.getParentFile().toPath());
            try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                w.write("# Cleanroom Unified\n");
                w.write("# This jar turns a Forge 1.12.2 instance into a Cleanroom instance by relaunching the game.\n\n");
                w.write("# Java used to run Cleanroom. Leave empty to use the managed Java runtime, which is downloaded once\n");
                w.write("# (through AssetMover, from Eclipse Adoptium) into <instance>/cleanroom/java.\n");
                w.write("# Otherwise give the path to a Java 25+ 'java' executable or Java home folder.\n");
                w.write("# On Windows use forward slashes or doubled backslashes, e.g. C:/Program Files/Java/jdk-25\n");
                w.write("javaPath=\n\n");
                w.write("# Feature version of the managed Java runtime. Must be 25 or newer.\n");
                w.write("managedJavaVersion=" + DEFAULT_JAVA_VERSION + "\n\n");
                w.write("# Extra JVM arguments for the relaunched game, separated by spaces. Memory settings (-Xmx etc.)\n");
                w.write("# are already carried over from the launcher.\n");
                w.write("extraJvmArgs=\n");
            }
        } catch (IOException e) {
            UnifiedLog.error("Could not write default " + file, e);
        }
    }
}
