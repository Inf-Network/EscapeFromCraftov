package net.infnetwork.snowball.craftov.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Crash-safe file replacement for the data files. New content goes into a temp file of its own
 * next to the target, is forced to the disk, and only then renamed over the target, so a crash or
 * a power cut leaves either the old file or the new one, never an empty or half-written one. Every
 * write gets its own temp name, so two writers of one file (the io thread and the final save on
 * shutdown) can never interleave inside the same temp file.
 */
public final class AtomicFiles {

    /** Every temp file ends in this; loaders and backups skip such names. */
    public static final String TEMP_SUFFIX = ".tmp";

    /**
     * Test hook, off unless the JVM is started with {@code -Dcraftov.test.write-delay-ms=N}: every
     * write first waits this long, a slow disk. tools/bot/marketio.js uses it to back the profile
     * writer up past the 10 s the shutdown waits for it, the case where an older queued copy could
     * land on the final save.
     */
    private static final long TEST_DELAY_MS = Math.max(0, Long.getLong("craftov.test.write-delay-ms", 0L));

    private AtomicFiles() {
    }

    public static void write(Path target, String content) throws IOException {
        if (TEST_DELAY_MS > 0) {
            try {
                Thread.sleep(TEST_DELAY_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(target.getFileName() + "." + Long.toHexString(ThreadLocalRandom.current().nextLong()) + TEMP_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // Without this a power cut can persist the rename before the data: an empty file.
                channel.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** The test write delay in milliseconds, 0 when it is off (always, outside tests). */
    public static long testDelayMs() {
        return TEST_DELAY_MS;
    }

    /** Whether {@code fileName} is a temp file left by {@link #write}. */
    public static boolean isTemp(String fileName) {
        return fileName.endsWith(TEMP_SUFFIX);
    }

    /**
     * Deletes temp files a crash left behind directly inside {@code dir}. Only call it while
     * nothing writes into that folder (on start).
     */
    public static void cleanTemp(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + TEMP_SUFFIX)) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // leftovers are harmless: they are never read
        }
    }
}
