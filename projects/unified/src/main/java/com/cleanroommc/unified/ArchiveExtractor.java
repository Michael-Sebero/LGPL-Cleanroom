package com.cleanroommc.unified;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts the Java runtime archives published by Adoptium: {@code .zip} on Windows, {@code .tar.gz} elsewhere.
 * <p>
 * AssetMover's archive handling goes through the zip filesystem, which loses the executable bits a JRE needs, so
 * tar archives go through {@link TarReader}: regular files, directories and symlinks, with their modes.
 */
final class ArchiveExtractor {

    private ArchiveExtractor() {
    }

    static void extract(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        String name = archive.getFileName().toString().toLowerCase();
        if (name.endsWith(".zip")) {
            extractZip(archive, destination);
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive)), 1 << 16)) {
                extractTar(in, destination);
            }
        } else {
            throw new IOException("Unsupported archive type: " + archive.getFileName());
        }
    }

    private static Path safeResolve(Path destination, String entryName) throws IOException {
        Path target = destination.resolve(entryName).normalize();
        if (!target.startsWith(destination)) {
            throw new IOException("Archive entry escapes the destination: " + entryName);
        }
        return target;
    }

    private static void extractZip(Path archive, Path destination) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = safeResolve(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target);
                    }
                }
            }
        }
    }

    private static void extractTar(InputStream in, Path destination) throws IOException {
        TarReader tar = new TarReader(in);
        TarReader.Member member;
        while ((member = tar.next()) != null) {
            Path target = safeResolve(destination, member.name);
            if (member.isDirectory()) {
                Files.createDirectories(target);
            } else if (member.isSymlink()) {
                Files.createDirectories(target.getParent());
                Path linkTarget = target.getParent().resolve(member.linkName).normalize();
                if (!linkTarget.startsWith(destination)) {
                    throw new IOException("Symlink escapes the destination: " + member.name + " -> " + member.linkName);
                }
                Files.deleteIfExists(target);
                Files.createSymbolicLink(target, target.getFileSystem().getPath(member.linkName));
            } else if (member.isFile()) {
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    tar.copyTo(out);
                }
                applyMode(target, member.mode);
            }
            // Hard links, devices, fifos: not present in JRE archives
        }
    }

    private static void applyMode(Path file, int mode) throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            if ((mode & 0100) != 0) {
                file.toFile().setExecutable(true);
            }
            return;
        }
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] order = {
                PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ
        };
        for (int i = 0; i < order.length; i++) {
            if ((mode & (1 << i)) != 0) {
                perms.add(order[i]);
            }
        }
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(file, perms);
    }

    static File findJavaExecutable(File javaHomeOrRoot, String executable) {
        File direct = new File(new File(javaHomeOrRoot, "bin"), executable);
        if (direct.isFile()) {
            return direct;
        }
        File[] children = javaHomeOrRoot.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isDirectory() && !Files.isSymbolicLink(child.toPath())) {
                File found = findJavaExecutable(child, executable);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
