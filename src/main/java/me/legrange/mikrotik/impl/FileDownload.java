package me.legrange.mikrotik.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import me.legrange.mikrotik.MikrotikApiException;

/**
 * Stores a remote file through a bounded chunk source without publishing stale
 * or partial data under the final target name.
 */
final class FileDownload {

    static final int CHUNK_SIZE = 32768;

    interface Source {
        long size() throws MikrotikApiException;

        byte[] read(long offset, int chunkSize) throws MikrotikApiException;
    }

    static long download(Path target, Source source) throws MikrotikApiException, IOException {
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (source == null) {
            throw new NullPointerException("source");
        }
        Path fileName = target.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("Target must name a file");
        }
        Path part = target.resolveSibling(fileName.toString() + ".part");

        Files.deleteIfExists(target);
        Files.deleteIfExists(part);

        try {
            long expectedSize = source.size();
            if (expectedSize < 0) {
                throw new ApiDataException("Remote file size must not be negative");
            }

            long offset = 0;
            try (OutputStream out = Files.newOutputStream(part,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                while (offset < expectedSize) {
                    int chunkSize = (int) Math.min((long) CHUNK_SIZE, expectedSize - offset);
                    byte[] data = source.read(offset, chunkSize);
                    if (data == null || data.length == 0) {
                        throw new ApiDataException("File download made no progress before reaching expected size");
                    }
                    if (data.length > expectedSize - offset) {
                        throw new ApiDataException("File download returned more data than the expected file size");
                    }
                    out.write(data);
                    offset += data.length;
                }
            }

            if (offset != expectedSize || Files.size(part) != expectedSize) {
                throw new ApiDataException("Downloaded file size does not match expected remote size");
            }

            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(part, target);
            }
            return expectedSize;
        } catch (MikrotikApiException | IOException | RuntimeException ex) {
            cleanup(target, part, ex);
            throw ex;
        }
    }

    private static void cleanup(Path target, Path part, Throwable primary) {
        cleanupOne(part, primary);
        cleanupOne(target, primary);
    }

    private static void cleanupOne(Path path, Throwable primary) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private FileDownload() {
    }
}
