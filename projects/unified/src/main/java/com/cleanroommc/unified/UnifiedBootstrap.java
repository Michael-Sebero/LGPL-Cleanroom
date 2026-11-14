package com.cleanroommc.unified;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

import java.io.File;
import java.util.List;

/**
 * The Forge-side flow: install Cleanroom (unpack and download), provision Java, relaunch, exit.
 */
final class UnifiedBootstrap {

    private UnifiedBootstrap() {
    }

    static void relaunchIntoCleanroom() {
        boolean client = FMLLaunchHandler.side().isClient();
        File gameDir = Platform.gameDir();
        StatusWindow status = new StatusWindow(client);
        int exitCode;
        try {
            UnifiedConfig config = UnifiedConfig.load(gameDir);
            BundledLibraries libraries = BundledLibraries.open();
            File cleanroomDir = new File(gameDir, "cleanroom");
            libraries.install(cleanroomDir, status);

            File java = JavaProvisioner.resolve(config, gameDir, client, status);
            List<File> mods = PatchMods.notInstalledByUser(libraries.mods(cleanroomDir), gameDir);
            List<String> command = Relauncher.command(java, client, libraries.classpath(cleanroomDir), mods, gameDir, config);
            status.close();

            UnifiedLog.info("Relaunching into Cleanroom {} on {}", libraries.cleanroomVersion(), java);
            UnifiedLog.info("Command: {}", Relauncher.describe(command));
            exitCode = Relauncher.run(command);
        } catch (Throwable t) {
            UnifiedLog.error("Could not start Cleanroom", t);
            status.error("Cleanroom could not be started:\n" + t + "\n\nSee the game log for details.");
            throw new RuntimeException("Cleanroom Unified could not relaunch the game into Cleanroom", t);
        }
        UnifiedLog.info("Cleanroom exited with code {}", exitCode);
        exit(exitCode);
    }

    private static void exit(int code) {
        if (System.getSecurityManager() == null) {
            System.exit(code);
        }
        // FML's security manager only lets FML (and Minecraft) end the JVM
        FMLCommonHandler.instance().exitJava(code, false);
    }
}
