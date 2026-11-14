package com.cleanroommc.unified;

import net.minecraft.launchwrapper.Launch;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds and runs the Cleanroom command line from the current (Forge) launch.
 */
final class Relauncher {

    static final String RELAUNCHED_PROPERTY = "cleanroom.unified.relaunched";

    private static final String CLIENT_MAIN = "top.outlands.foundation.boot.Foundation";
    private static final String CLIENT_TWEAKER = "net.minecraftforge.fml.common.launcher.FMLTweaker";
    private static final String SERVER_MAIN = "net.minecraftforge.fml.relauncher.ServerLaunchWrapper";

    /** Vanilla libraries Cleanroom keeps (see mmcpack-template/patches/net.minecraft.json); everything else it replaces. */
    private static final Set<String> KEPT_VANILLA_LIBRARIES = new HashSet<>(Arrays.asList(
            "codecjorbis", "codecwav", "libraryjavasound", "librarylwjglopenal", "soundsystem",
            "authlib", "realms", "text2speech"));

    /** JVM arguments Cleanroom is developed and run with (ProjectConstants.JVM_ARGUMENTS) plus quieting Java 25 native-access warnings. */
    private static final List<String> CLEANROOM_JVM_ARGS = Arrays.asList(
            "--add-opens=java.base/jdk.internal.loader=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED");

    /** System properties that belong to the old (Java 8 / LWJGL 2) launch and must not leak into the new one. */
    private static final Set<String> DROPPED_PROPERTIES = new HashSet<>(Arrays.asList(
            "java.library.path", "java.class.path", "org.lwjgl.librarypath", "net.java.games.input.librarypath",
            "java.endorsed.dirs", "java.ext.dirs", "java.security.manager", "sun.boot.library.path", RELAUNCHED_PROPERTY));

    private Relauncher() {
    }

    static List<String> command(File java, boolean client, List<File> cleanroomClasspath, List<File> mods, File gameDir,
                                UnifiedConfig config) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(java.getAbsolutePath());

        // JVM options
        cmd.add("-XX:+IgnoreUnrecognizedVMOptions"); // old launcher profiles carry Java 8-only -XX flags
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.addAll(CLEANROOM_JVM_ARGS);
        if (client && Platform.OS_TYPE == Platform.OS.MAC) {
            cmd.add("-XstartOnFirstThread"); // GLFW (LWJGL 3) requires the main thread on macOS
        }
        cmd.addAll(inheritedJvmArgs());
        for (String arg : config.extraJvmArgs.split("\\s+")) {
            if (!arg.isEmpty()) {
                cmd.add(arg);
            }
        }
        cmd.add("-D" + RELAUNCHED_PROPERTY + "=true");

        // Classpath: kept vanilla libraries, Cleanroom + its libraries, then the vanilla game jar (as Cleanroom's own launcher profile does)
        Set<String> cp = new LinkedHashSet<>();
        if (client) {
            cp.addAll(keptVanillaLibraries());
        }
        for (File f : cleanroomClasspath) {
            cp.add(f.getAbsolutePath());
        }
        cp.add(minecraftJar(client).getAbsolutePath());
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator, cp));

        // Main class and game arguments
        List<String> args = client ? clientArgs() : serverArgs();
        addMods(args, mods, gameDir);
        if (client) {
            cmd.add(CLIENT_MAIN);
            cmd.addAll(args);
            cmd.add("--tweakClass");
            cmd.add(CLIENT_TWEAKER);
        } else {
            cmd.add(SERVER_MAIN);
            cmd.addAll(args);
        }
        return cmd;
    }

    /**
     * Loads the bundled patch mods through FML's {@code --mods} argument: comma-separated paths relative to the game
     * folder. Merged into an existing {@code --mods} if the launcher passed one. Put first so that no value-less flag
     * can precede it (FML's argument parser drops an option that directly follows one, e.g. {@code --demo}).
     */
    private static void addMods(List<String> args, List<File> mods, File gameDir) {
        if (mods.isEmpty()) {
            return;
        }
        List<String> paths = new ArrayList<>();
        for (File mod : mods) {
            paths.add(gameDir.toPath().toAbsolutePath().normalize()
                    .relativize(mod.toPath().toAbsolutePath().normalize()).toString().replace('\\', '/'));
        }
        String joined = String.join(",", paths);
        int existing = args.indexOf("--mods");
        if (existing >= 0 && existing + 1 < args.size()) {
            args.set(existing + 1, args.get(existing + 1) + "," + joined);
        } else {
            args.add(0, "--mods");
            args.add(1, joined);
        }
    }

    /**
     * Runs the game and waits for it. If this (parent) process is stopped, the game is stopped too.
     */
    static int run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).inheritIO().start();
        System.gc(); // this JVM now only waits; give back what it can
        Thread killChild = new Thread(process::destroy, "Cleanroom Unified child reaper");
        Runtime.getRuntime().addShutdownHook(killChild);
        try {
            return process.waitFor();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(killChild);
            } catch (IllegalStateException ignored) { // already shutting down
            }
        }
    }

    /** For logging: the command with the access token hidden. */
    static String describe(List<String> command) {
        StringBuilder sb = new StringBuilder();
        boolean hideNext = false;
        for (String arg : command) {
            sb.append(' ').append(hideNext ? "<hidden>" : arg);
            hideNext = "--accessToken".equals(arg);
        }
        return sb.substring(1);
    }

    private static List<String> inheritedJvmArgs() {
        List<String> kept = new ArrayList<>();
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-Xmx") || arg.startsWith("-Xms") || arg.startsWith("-Xss") || arg.startsWith("-Xmn") || arg.startsWith("-XX:")) {
                kept.add(arg);
            } else if (arg.startsWith("-D")) {
                int eq = arg.indexOf('=');
                String key = arg.substring(2, eq < 0 ? arg.length() : eq);
                if (!DROPPED_PROPERTIES.contains(key)) {
                    kept.add(arg);
                }
            }
            // Everything else (-javaagent, -agentlib, -Xbootclasspath, -Xincgc, ...) is Java 8 / launch specific
        }
        return kept;
    }

    private static List<String> keptVanillaLibraries() {
        List<String> kept = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            File f = new File(entry);
            if (!f.isFile() || !entry.endsWith(".jar")) {
                continue;
            }
            // Launchers use Maven layout (.../<artifact>/<version>/<artifact>-<version>.jar), but match the file name too
            File versionDir = f.getParentFile();
            File artifactDir = versionDir == null ? null : versionDir.getParentFile();
            String artifact = artifactDir == null ? "" : artifactDir.getName();
            if (KEPT_VANILLA_LIBRARIES.contains(artifact) || KEPT_VANILLA_LIBRARIES.contains(artifactOfFileName(f.getName()))) {
                kept.add(f.getAbsolutePath());
            }
        }
        return kept;
    }

    /** {@code authlib-1.5.25.jar -> authlib}: the part before the first "-<digit>". */
    static String artifactOfFileName(String fileName) {
        for (int i = 0; i + 1 < fileName.length(); i++) {
            if (fileName.charAt(i) == '-' && Character.isDigit(fileName.charAt(i + 1))) {
                return fileName.substring(0, i);
            }
        }
        return "";
    }

    private static File minecraftJar(boolean client) throws IOException {
        String marker = client ? "net/minecraft/client/main/Main.class" : "net/minecraft/server/MinecraftServer.class";
        URL url = ClassLoader.getSystemClassLoader().getResource(marker);
        if (url == null || !"jar".equals(url.getProtocol())) {
            throw new IOException("Cannot find the vanilla Minecraft jar on the classpath (looked for " + marker + ")");
        }
        return Platform.fileOf(url);
    }

    @SuppressWarnings("unchecked")
    private static List<String> clientArgs() {
        List<String> args = new ArrayList<>();
        Object launchArgs = Launch.blackboard == null ? null : Launch.blackboard.get("launchArgs");
        if (launchArgs instanceof Map) {
            // Filled by FMLTweaker from the original command line, before coremods are constructed.
            // Value-less flags (--demo, --fullscreen) are passed as "--flag=": FML's parser drops the option after a
            // bare flag, and a bare flag at the very end. Both forms reach Minecraft the same way.
            List<String> flags = new ArrayList<>();
            for (Map.Entry<String, String> e : ((Map<String, String>) launchArgs).entrySet()) {
                if ("--tweakClass".equals(e.getKey())) {
                    continue;
                }
                if (e.getValue() == null || e.getValue().isEmpty()) {
                    flags.add(e.getKey() + "=");
                } else {
                    args.add(e.getKey());
                    args.add(e.getValue());
                }
            }
            args.addAll(flags);
            return args;
        }
        return stripTweakers(originalProgramArgs());
    }

    private static List<String> serverArgs() {
        return stripTweakers(originalProgramArgs());
    }

    /** Program arguments from {@code sun.java.command}, minus the main class or jar. */
    private static List<String> originalProgramArgs() {
        String command = System.getProperty("sun.java.command", "").trim();
        String classPath = System.getProperty("java.class.path", "");
        String rest;
        if (!classPath.isEmpty() && command.startsWith(classPath)) {
            rest = command.substring(classPath.length()); // "java -jar <jar>": the jar path may contain spaces
        } else {
            int space = command.indexOf(' ');
            rest = space < 0 ? "" : command.substring(space);
        }
        List<String> args = new ArrayList<>();
        for (String arg : rest.trim().split("\\s+")) {
            if (!arg.isEmpty()) {
                args.add(arg);
            }
        }
        return args;
    }

    private static List<String> stripTweakers(Collection<String> in) {
        List<String> out = new ArrayList<>();
        boolean skip = false;
        for (String arg : in) {
            if (skip) {
                skip = false;
            } else if ("--tweakClass".equals(arg)) {
                skip = true;
            } else if (!arg.startsWith("--tweakClass=")) {
                out.add(arg);
            }
        }
        return out;
    }
}
