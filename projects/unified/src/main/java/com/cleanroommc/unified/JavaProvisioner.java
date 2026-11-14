package com.cleanroommc.unified;

import com.cleanroommc.assetmover.UnifiedDownload;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Finds the Java runtime used for Cleanroom: the configured one, or a managed Eclipse Temurin runtime that is
 * downloaded once (through AssetMover) into {@code <instance>/cleanroom/java/} and reused on every later launch.
 */
final class JavaProvisioner {

    private static final String ADOPTIUM_API = "https://api.adoptium.net/v3/assets/latest/%d/hotspot?os=%s&architecture=%s&image_type=%s&vendor=eclipse";
    private static final String MARKER = ".installed";

    private JavaProvisioner() {
    }

    static File resolve(UnifiedConfig config, File gameDir, boolean client, StatusWindow status) throws IOException {
        // javaw has no console window on Windows; the client is a GUI app started from a GUI launcher
        String exe = Platform.executableName(client && Platform.OS_TYPE == Platform.OS.WINDOWS ? "javaw" : "java");

        if (!config.javaPath.isEmpty()) {
            File configured = new File(config.javaPath);
            File java = configured.isDirectory() ? ArchiveExtractor.findJavaExecutable(configured, exe) : configured;
            if (java == null || !java.isFile()) {
                throw new IOException("javaPath in config/" + UnifiedConfig.FILE_NAME + " does not point to a Java executable: " + config.javaPath);
            }
            UnifiedLog.info("Using configured Java {}", java);
            return java;
        }

        int version = config.managedJavaVersion;
        File home = new File(gameDir, "cleanroom/java/temurin-" + version + "-" + Platform.OS_TYPE.adoptium + "-" + Platform.ARCH);
        File installed = installedExecutable(home, exe);
        if (installed != null) {
            return installed;
        }
        install(version, home, status);
        installed = installedExecutable(home, exe);
        if (installed == null) {
            throw new IOException("Java " + version + " was installed into " + home + " but no " + exe + " was found in it");
        }
        return installed;
    }

    private static File installedExecutable(File home, String exe) {
        if (!new File(home, MARKER).isFile()) {
            return null;
        }
        File java = ArchiveExtractor.findJavaExecutable(home, exe);
        return java != null && java.isFile() ? java : null;
    }

    private static void install(int version, File home, StatusWindow status) throws IOException {
        status.show("Looking up Java " + version + " for " + Platform.OS_TYPE.adoptium + "/" + Platform.ARCH + "...");
        ModernTrust.install();
        JsonObject pkg = findPackage(version);
        String name = pkg.get("name").getAsString();
        String link = pkg.get("link").getAsString();
        String checksum = pkg.get("checksum").getAsString();
        long size = pkg.has("size") ? pkg.get("size").getAsLong() : -1;

        status.show("Downloading " + name + (size > 0 ? " (" + (size >> 20) + " MB)" : "") + "...");
        Path archive = UnifiedDownload.download(link, checksum, "cleanroom-unified/" + name);

        status.show("Unpacking Java " + version + "...");
        File staging = new File(home.getParentFile(), home.getName() + ".partial");
        deleteRecursively(staging.toPath());
        try {
            ArchiveExtractor.extract(archive, staging.toPath());
            File java = ArchiveExtractor.findJavaExecutable(staging, Platform.executableName("java"));
            if (java == null) {
                throw new IOException(name + " does not contain a java executable");
            }
            checkVersion(java, version);
            deleteRecursively(home.toPath());
            Files.move(staging.toPath(), home.toPath());
            Files.write(new File(home, MARKER).toPath(), (name + "\n").getBytes(StandardCharsets.UTF_8));
        } finally {
            deleteRecursively(staging.toPath());
            Files.deleteIfExists(archive);
            try {
                Files.deleteIfExists(archive.getParent()); // only succeeds when empty
            } catch (IOException ignored) {
            }
        }
        UnifiedLog.info("Installed {} into {}", name, home);
    }

    /** The JRE package if Adoptium has one for this platform, else the JDK. */
    private static JsonObject findPackage(int version) throws IOException {
        for (String imageType : new String[]{"jre", "jdk"}) {
            String url = String.format(ADOPTIUM_API, version, Platform.OS_TYPE.adoptium, Platform.ARCH, imageType);
            JsonElement json = getJson(url);
            if (json != null && json.isJsonArray()) {
                JsonArray releases = json.getAsJsonArray();
                if (releases.size() > 0) {
                    return releases.get(0).getAsJsonObject().getAsJsonObject("binary").getAsJsonObject("package");
                }
            }
        }
        throw new IOException("Eclipse Adoptium has no Java " + version + " build for " + Platform.OS_TYPE.adoptium + "/" + Platform.ARCH
                + ". Install Java " + version + " yourself and set javaPath in config/" + UnifiedConfig.FILE_NAME);
    }

    private static JsonElement getJson(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "CleanroomUnified");
        connection.setRequestProperty("Accept", "application/json");
        int code = connection.getResponseCode();
        if (code == 404) {
            return null;
        }
        if (code != 200) {
            throw new IOException("HTTP " + code + " from " + url);
        }
        try (Reader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            return new JsonParser().parse(reader);
        }
    }

    /** Reads the runtime's {@code release} file rather than starting a process. */
    private static void checkVersion(File java, int expected) throws IOException {
        File release = new File(java.getParentFile().getParentFile(), "release");
        if (!release.isFile()) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(release.toPath())) {
            p.load(in);
        }
        String value = p.getProperty("JAVA_VERSION", "").replace("\"", "");
        String major = value.split("[.+-]")[0];
        if (!major.isEmpty() && Integer.parseInt(major) < expected) {
            throw new IOException("Downloaded Java reports version " + value + ", expected " + expected + "+");
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (java.util.stream.Stream<Path> children = Files.list(path)) {
                for (Path child : (Iterable<Path>) children::iterator) {
                    deleteRecursively(child);
                }
            }
        }
        Files.delete(path);
    }
}
