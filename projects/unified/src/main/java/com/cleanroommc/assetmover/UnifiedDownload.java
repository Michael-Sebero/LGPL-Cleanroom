package com.cleanroommc.assetmover;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Bridge into AssetMover's (package-private) download engine.
 * <p>
 * This class lives in AssetMover's package so it can use AssetMover's downloader and checksum verification directly
 * at coremod time. AssetMover's request queue ({@code AssetMover.run()}) reports through FML's ProgressManager, which
 * initializes FML's Loader; that is fine during mod construction but not something to trigger before Minecraft has
 * even been loaded, so the queue is bypassed.
 * <p>
 * At build time this class is relocated together with the bundled AssetMover classes into a private package, so it
 * never touches (or clashes with) a standalone AssetMover the user may have installed.
 */
public final class UnifiedDownload {

    private UnifiedDownload() {
    }

    /**
     * Downloads {@code url} to {@code <instance>/assetmover/<destination>}, verifying it against {@code checksum}
     * (MD5/SHA-1/SHA-256/SHA-512, chosen by length). Blocks until done.
     */
    public static Path download(String url, String checksum, String destination) throws IOException {
        Path temp = AssetMoverHelper.download(url, "url");
        try {
            AssetMoverHelper.verify(temp, AssetMoverHelper.algorithmOf(checksum), checksum);
            Path file = AssetMover.PACK.resolve(destination);
            AssetMoverHelper.copy(temp, file);
            return file;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Downloads {@code url} to {@code target}, verifying it against {@code checksum} first. The target is replaced only
     * by a complete, verified file. Safe to call from several threads at once.
     */
    public static void downloadTo(String url, String checksum, Path target) throws IOException {
        Path temp = AssetMoverHelper.download(url, "url");
        try {
            AssetMoverHelper.verify(temp, AssetMoverHelper.algorithmOf(checksum), checksum);
            Files.createDirectories(target.getParent());
            Path part = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
            try {
                Files.copy(temp, part, StandardCopyOption.REPLACE_EXISTING);
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(part);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
