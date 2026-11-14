package com.cleanroommc.unified;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The patch mods bundled with Cleanroom (Fugue, Scalar). Each is identified by its coremod class; if the player
 * already has a copy in {@code mods/}, theirs is used and the bundled one is left out (two copies of one mod id
 * would stop the game).
 */
final class PatchMods {

    private PatchMods() {
    }

    static List<File> notInstalledByUser(List<File> bundled, File gameDir) {
        Set<String> installed = new HashSet<>();
        for (File dir : new File[]{new File(gameDir, "mods"), new File(gameDir, "mods/1.12.2")}) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".jar"));
            if (files == null) {
                continue;
            }
            for (File f : files) {
                String coremod = coremodOf(f);
                if (coremod != null) {
                    installed.add(coremod);
                }
            }
        }
        List<File> result = new ArrayList<>();
        for (File mod : bundled) {
            String coremod = coremodOf(mod);
            if (coremod != null && installed.contains(coremod)) {
                UnifiedLog.info("{} is already installed in mods/, using that copy instead of the bundled one", mod.getName());
            } else {
                result.add(mod);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static String coremodOf(File jar) {
        try (JarFile jf = new JarFile(jar)) {
            Manifest manifest = jf.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue("FMLCorePlugin");
        } catch (IOException e) {
            return null;
        }
    }
}
