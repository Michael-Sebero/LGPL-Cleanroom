package com.cleanroommc.unified;

import com.cleanroommc.assetmover.UnifiedDownload;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Cleanroom, its libraries and the patch mods it expects (Fugue, Scalar), installed into {@code <instance>/cleanroom/}:
 * libraries in Maven layout under {@code libraries/} (Cleanroom's LibraryManager expects that layout), mods under
 * {@code mods/}.
 * <p>
 * {@code index.tsv} under {@link #ROOT} lists every file as {@code kind<TAB>path<TAB>sha1<TAB>size<TAB>source}, libraries
 * in classpath order. {@code kind} is {@code cleanroom}, {@code lib}, {@code mod}, or an LWJGL natives classifier such as
 * {@code natives-linux}. {@code path} is relative to the install folder. {@code source} is {@code bundled} (the file is
 * in this jar under {@link #ROOT}{@code path}) or the URL it is downloaded from. Every file is checked against its
 * SHA-1 before it is used.
 */
final class BundledLibraries {

    static final String ROOT = "META-INF/cleanroom-unified/";
    private static final String STAMP = ".unified-stamp";
    private static final String MOD = "mod";
    private static final String BUNDLED = "bundled";
    private static final int DOWNLOAD_THREADS = 4;
    private static final int DOWNLOAD_ATTEMPTS = 3;

    static final class Entry {
        final String kind;
        final String path;
        final String sha1;
        final long size;
        final String source;

        Entry(String kind, String path, String sha1, long size, String source) {
            this.kind = kind;
            this.path = path;
            this.sha1 = sha1;
            this.size = size;
            this.source = source;
        }

        boolean bundled() {
            return BUNDLED.equals(source);
        }

        /** LWJGL natives for other platforms are never needed on this machine. */
        boolean neededHere() {
            return !kind.startsWith("natives-") || kind.equals(Platform.lwjglNativesClassifier());
        }
    }

    final File jar;
    final Properties info = new Properties();
    final List<Entry> entries = new ArrayList<>();
    private String indexHash;

    private BundledLibraries(File jar) {
        this.jar = jar;
    }

    static BundledLibraries open() throws IOException {
        BundledLibraries libs = new BundledLibraries(locateOwnJar());
        try (ZipFile zip = new ZipFile(libs.jar)) {
            ZipEntry info = zip.getEntry(ROOT + "info.properties");
            ZipEntry index = zip.getEntry(ROOT + "index.tsv");
            if (info == null || index == null) {
                throw new IOException(libs.jar + " does not contain the Cleanroom index");
            }
            try (InputStream in = zip.getInputStream(info)) {
                libs.info.load(in);
            }
            MessageDigest digest = sha1();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(zip.getInputStream(index), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    digest.update(line.getBytes(StandardCharsets.UTF_8));
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split("\t");
                    if (parts.length != 5) {
                        throw new IOException("Malformed index line: " + line);
                    }
                    libs.entries.add(new Entry(parts[0], parts[1], parts[2], Long.parseLong(parts[3]), parts[4]));
                }
            }
            libs.indexHash = hex(digest.digest());
        }
        return libs;
    }

    String cleanroomVersion() {
        return info.getProperty("cleanroomVersion", "unknown");
    }

    /**
     * Installs every file this machine needs that is missing or damaged: bundled ones from this jar, the rest
     * downloaded. Cheap when nothing changed: only presence and sizes are checked.
     */
    void install(File cleanroomDir, StatusWindow status) throws IOException {
        File stamp = new File(cleanroomDir, STAMP);
        if (stamp.isFile() && indexHash.equals(new String(Files.readAllBytes(stamp.toPath()), StandardCharsets.UTF_8).trim())
                && allPresent(cleanroomDir)) {
            return;
        }
        status.show("Checking Cleanroom " + cleanroomVersion() + "...");
        Files.createDirectories(cleanroomDir.toPath());
        List<Entry> toDownload = new ArrayList<>();
        int checked = 0;
        int needed = 0;
        for (Entry e : entries) {
            if (e.neededHere()) {
                needed++;
            }
        }
        try (ZipFile zip = new ZipFile(jar)) {
            for (Entry e : entries) {
                if (!e.neededHere()) {
                    continue;
                }
                status.progress(checked++, needed);
                File target = new File(cleanroomDir, e.path);
                if (target.isFile() && target.length() == e.size && e.sha1.equals(sha1Of(target.toPath()))) {
                    continue;
                }
                if (e.bundled()) {
                    extract(zip, e, target);
                } else {
                    toDownload.add(e);
                }
            }
        }
        if (!toDownload.isEmpty()) {
            download(toDownload, cleanroomDir, status);
        }
        Files.write(stamp.toPath(), indexHash.getBytes(StandardCharsets.UTF_8));
        UnifiedLog.info("Cleanroom {} is installed in {} ({} files checked, {} downloaded)", cleanroomVersion(), cleanroomDir,
                needed, toDownload.size());
    }

    private static void extract(ZipFile zip, Entry e, File target) throws IOException {
        ZipEntry ze = zip.getEntry(ROOT + e.path);
        if (ze == null) {
            throw new IOException("Bundled file is missing from the jar: " + e.path);
        }
        Files.createDirectories(target.getParentFile().toPath());
        Path tmp = Files.createTempFile(target.getParentFile().toPath(), target.getName(), ".part");
        try (InputStream in = zip.getInputStream(ze)) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            if (!e.sha1.equals(sha1Of(tmp))) {
                throw new IOException("Checksum mismatch while unpacking " + e.path);
            }
            Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Downloads the given libraries a few at a time; each is verified against its SHA-1 before it is put in place. */
    private void download(List<Entry> toDownload, File cleanroomDir, StatusWindow status) throws IOException {
        long total = 0;
        for (Entry e : toDownload) {
            total += e.size;
        }
        final long totalBytes = Math.max(total, 1);
        status.show("Downloading " + toDownload.size() + " Cleanroom libraries (" + (total >> 20) + " MB)...");
        UnifiedLog.info("Downloading {} libraries ({} MB)", toDownload.size(), total >> 20);
        ModernTrust.install();

        AtomicLong done = new AtomicLong();
        AtomicInteger counter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(DOWNLOAD_THREADS, r -> {
            Thread t = new Thread(r, "Cleanroom Unified download " + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            for (Entry e : toDownload) {
                futures.add(pool.submit(() -> {
                    Path target = new File(cleanroomDir, e.path).toPath();
                    IOException last = null;
                    for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
                        try {
                            UnifiedDownload.downloadTo(e.source, e.sha1, target);
                            status.progress((int) (done.addAndGet(e.size) * 1000 / totalBytes), 1000);
                            return;
                        } catch (IOException ex) {
                            last = ex;
                            UnifiedLog.warn("Download of {} failed (attempt {} of {}): {}", e.source, attempt, DOWNLOAD_ATTEMPTS, ex.toString());
                        }
                    }
                    failures.add(e.source + " (" + last + ")");
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while downloading libraries", ex);
                } catch (java.util.concurrent.ExecutionException ex) {
                    failures.add(String.valueOf(ex.getCause()));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (!failures.isEmpty()) {
            StringBuilder message = new StringBuilder("Could not download ").append(failures.size())
                    .append(" of ").append(toDownload.size()).append(" Cleanroom libraries. Check your internet connection and start the game again")
                    .append(" (finished downloads are kept). First failures:");
            for (String f : failures.subList(0, Math.min(3, failures.size()))) {
                message.append("\n  ").append(f);
            }
            throw new IOException(message.toString());
        }
    }

    private boolean allPresent(File cleanroomDir) {
        for (Entry e : entries) {
            if (!e.neededHere()) {
                continue;
            }
            File f = new File(cleanroomDir, e.path);
            if (!f.isFile() || f.length() != e.size) {
                return false;
            }
        }
        return true;
    }

    /**
     * Classpath entries for this machine: Cleanroom, all libraries, and only the LWJGL natives for this OS/arch.
     */
    List<File> classpath(File cleanroomDir) {
        List<File> cp = new ArrayList<>();
        for (Entry e : entries) {
            if (!MOD.equals(e.kind) && e.neededHere()) {
                cp.add(new File(cleanroomDir, e.path));
            }
        }
        return Collections.unmodifiableList(cp);
    }

    /** The bundled patch mods (Fugue, Scalar), to be loaded as mods rather than put on the classpath. */
    List<File> mods(File cleanroomDir) {
        List<File> mods = new ArrayList<>();
        for (Entry e : entries) {
            if (MOD.equals(e.kind)) {
                mods.add(new File(cleanroomDir, e.path));
            }
        }
        // cleanroom/mods only ever holds bundled mods: drop ones left behind by an older version of this jar
        File[] present = new File(cleanroomDir, "mods").listFiles();
        if (present != null) {
            for (File f : present) {
                if (f.isFile() && !mods.contains(f) && f.delete()) {
                    UnifiedLog.info("Removed {} from an older Cleanroom Unified", f.getName());
                }
            }
        }
        return Collections.unmodifiableList(mods);
    }

    private static File locateOwnJar() throws IOException {
        // Under Forge, LaunchClassLoader reports the class's own URL (jar:file:/...jar!/com/...class) as the code
        // source rather than the jar's file: URL, so both forms are handled
        CodeSource source = BundledLibraries.class.getProtectionDomain().getCodeSource();
        java.net.URL url = source == null ? null : source.getLocation();
        if (url == null) {
            url = BundledLibraries.class.getClassLoader().getResource(BundledLibraries.class.getName().replace('.', '/') + ".class");
        }
        if (url == null) {
            throw new IOException("Cannot locate the Cleanroom Unified jar");
        }
        File file = Platform.fileOf(url);
        if (!file.isFile()) {
            throw new IOException("Cleanroom Unified is not running from a jar: " + file);
        }
        return file;
    }

    static String sha1Of(Path file) throws IOException {
        MessageDigest digest = sha1();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
