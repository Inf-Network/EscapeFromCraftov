package net.infnetwork.snowball.craftov.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One data file written from more than one thread, where the newest copy must be the one that
 * stays. Every copy of the content is numbered when it is taken, and a copy that comes up for
 * writing after a newer one was written is dropped instead of landing on top of it.
 *
 * <p>The io thread writes its copies in the order they were taken anyway. This is for shutdown:
 * the final save writes from the main thread once the wait for the io thread is over, and when
 * that wait timed out, copies taken before the final one may still be queued behind it. Two
 * writers never share a temp file ({@link AtomicFiles#write}) and never run at the same time: the
 * second one waits for the first one's rename, then writes only if it holds the newer copy.
 */
public final class OrderedFile {

    private final Path target;
    private final AtomicLong taken = new AtomicLong();
    /** Number of the copy on disk, 0 before the first write. Guarded by this. */
    private long written;

    public OrderedFile(Path target) {
        this.target = target;
    }

    public Path path() {
        return target;
    }

    /** Numbers the copy of the content being taken now; call it right where the content is taken. */
    public long take() {
        return taken.incrementAndGet();
    }

    /**
     * Writes copy {@code copy} unless a newer one is already on disk. Returns false when it was
     * dropped for that reason.
     */
    public synchronized boolean write(long copy, String content) throws IOException {
        if (copy <= written) {
            return false;
        }
        AtomicFiles.write(target, content);
        written = copy;
        return true;
    }
}
