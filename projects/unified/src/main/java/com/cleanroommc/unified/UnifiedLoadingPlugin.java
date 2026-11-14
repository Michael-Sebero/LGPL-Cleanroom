package com.cleanroommc.unified;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Entry point of the unified Cleanroom jar.
 * <p>
 * Dropped into the {@code mods} folder of a regular Forge 1.12.2 instance, Forge instantiates this class very early
 * (before Minecraft itself is loaded). On that first, Forge-on-Java-8 launch we:
 * <ol>
 *     <li>unpack Cleanroom and every library it needs from inside this jar,</li>
 *     <li>make sure a Java 25 runtime is available, downloading it once through AssetMover if needed,</li>
 *     <li>start a new game process on Java 25 with Cleanroom as the loader, and exit when it exits.</li>
 * </ol>
 * Inside the relaunched Cleanroom process this jar is still in the mods folder; there it does nothing.
 */
@IFMLLoadingPlugin.Name("CleanroomUnified")
@IFMLLoadingPlugin.MCVersion("1.12.2")
public class UnifiedLoadingPlugin implements IFMLLoadingPlugin {

    public UnifiedLoadingPlugin() {
        if (Platform.isCleanroom()) {
            UnifiedLog.info("Running inside Cleanroom, nothing to do.");
            return;
        }
        if (Boolean.getBoolean(Relauncher.RELAUNCHED_PROPERTY)) {
            // The child process should always be Cleanroom. If it is not, relaunching again would loop forever.
            throw new IllegalStateException("This process was relaunched by Cleanroom Unified but Cleanroom is not on the classpath. "
                    + "The unpacked libraries may be damaged; delete the 'cleanroom/libraries' folder in the instance and try again.");
        }
        UnifiedBootstrap.relaunchIntoCleanroom();
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
