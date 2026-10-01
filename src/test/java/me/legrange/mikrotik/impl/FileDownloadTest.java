package me.legrange.mikrotik.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.legrange.mikrotik.MikrotikApiException;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FileDownloadTest {

    @Test
    public void downloadsMultipleShortChunksUsingActualOffsets() throws Exception {
        Path dir = Files.createTempDirectory("file-download");
        Path target = dir.resolve("config.bin");
        final byte[] data = payload(70013);
        final List<Long> offsets = new ArrayList<>();
        final List<Integer> requested = new ArrayList<>();

        long bytes = FileDownload.download(target, new FileDownload.Source() {
            @Override
            public long size() {
                return data.length;
            }

            @Override
            public byte[] read(long offset, int chunkSize) {
                offsets.add(offset);
                requested.add(chunkSize);
                int available = data.length - (int) offset;
                int length = Math.min(Math.min(chunkSize, 10000), available);
                return Arrays.copyOfRange(data, (int) offset, (int) offset + length);
            }
        });

        assertEquals(data.length, bytes);
        assertArrayEquals(data, Files.readAllBytes(target));
        assertEquals(Arrays.asList(0L, 10000L, 20000L, 30000L, 40000L, 50000L, 60000L, 70000L), offsets);
        assertEquals(Integer.valueOf(FileDownload.CHUNK_SIZE), requested.get(0));
        assertEquals(Integer.valueOf(13), requested.get(requested.size() - 1));
        assertFalse(Files.exists(part(target)));
    }

    @Test
    public void zeroByteFileDoesNotReadSource() throws Exception {
        Path target = Files.createTempDirectory("file-download-zero").resolve("empty.bin");
        final int[] reads = {0};

        long bytes = FileDownload.download(target, new FileDownload.Source() {
            @Override
            public long size() {
                return 0;
            }

            @Override
            public byte[] read(long offset, int chunkSize) {
                reads[0]++;
                return new byte[0];
            }
        });

        assertEquals(0, bytes);
        assertEquals(0, reads[0]);
        assertEquals(0, Files.size(target));
    }

    @Test
    public void sourceSizeFailureRemovesOldTargetAndPart() throws Exception {
        Path dir = Files.createTempDirectory("file-download-stale");
        Path target = dir.resolve("config.bin");
        Files.write(target, new byte[]{9});
        Files.write(part(target), new byte[]{8});

        try {
            FileDownload.download(target, new FileDownload.Source() {
                @Override
                public long size() throws MikrotikApiException {
                    throw new MikrotikApiException("lookup failed");
                }

                @Override
                public byte[] read(long offset, int chunkSize) {
                    fail("read must not be called");
                    return null;
                }
            });
            fail("Expected MikrotikApiException");
        } catch (MikrotikApiException expected) {
            assertEquals("lookup failed", expected.getMessage());
        }

        assertNoPublishedOrPartialFile(target);
    }

    @Test
    public void zeroProgressFailsAndCleansPartialFile() throws Exception {
        Path target = Files.createTempDirectory("file-download-progress").resolve("config.bin");

        expectApiFailure(target, new FileDownload.Source() {
            @Override
            public long size() {
                return 10;
            }

            @Override
            public byte[] read(long offset, int chunkSize) {
                return new byte[0];
            }
        });

        assertNoPublishedOrPartialFile(target);
    }

    @Test
    public void oversizedChunkFailsAndCleansPartialFile() throws Exception {
        Path target = Files.createTempDirectory("file-download-overrun").resolve("config.bin");

        expectApiFailure(target, new FileDownload.Source() {
            @Override
            public long size() {
                return 3;
            }

            @Override
            public byte[] read(long offset, int chunkSize) {
                return new byte[]{1, 2, 3, 4};
            }
        });

        assertNoPublishedOrPartialFile(target);
    }

    @Test
    public void sourceFailureAfterOneChunkCleansPartialFile() throws Exception {
        Path target = Files.createTempDirectory("file-download-source-error").resolve("config.bin");
        final int[] reads = {0};

        try {
            FileDownload.download(target, new FileDownload.Source() {
                @Override
                public long size() {
                    return 20;
                }

                @Override
                public byte[] read(long offset, int chunkSize) throws MikrotikApiException {
                    if (reads[0]++ == 0) {
                        return new byte[10];
                    }
                    throw new MikrotikApiException("read failed");
                }
            });
            fail("Expected MikrotikApiException");
        } catch (MikrotikApiException expected) {
            assertEquals("read failed", expected.getMessage());
        }

        assertNoPublishedOrPartialFile(target);
    }

    @Test
    public void failedRequiredDeletionDoesNotContactSource() throws Exception {
        Path dir = Files.createTempDirectory("file-download-reset");
        Path target = dir.resolve("config.bin");
        Files.createDirectory(target);
        Files.write(target.resolve("child"), new byte[]{1});
        final int[] sourceCalls = {0};

        try {
            FileDownload.download(target, new FileDownload.Source() {
                @Override
                public long size() {
                    sourceCalls[0]++;
                    return 0;
                }

                @Override
                public byte[] read(long offset, int chunkSize) {
                    sourceCalls[0]++;
                    return new byte[0];
                }
            });
            fail("Expected IOException");
        } catch (IOException expected) {
            // expected: a non-empty directory cannot be removed as the target file
        }

        assertEquals(0, sourceCalls[0]);
    }

    @Test
    public void missingParentDirectoryIsNotCreated() throws Exception {
        Path dir = Files.createTempDirectory("file-download-parent");
        Path parent = dir.resolve("missing");
        Path target = parent.resolve("config.bin");

        try {
            FileDownload.download(target, new FileDownload.Source() {
                @Override
                public long size() {
                    return 0;
                }

                @Override
                public byte[] read(long offset, int chunkSize) {
                    return new byte[0];
                }
            });
            fail("Expected IOException");
        } catch (IOException expected) {
            // expected
        }

        assertFalse(Files.exists(parent));
    }

    private static void expectApiFailure(Path target, FileDownload.Source source) throws Exception {
        try {
            FileDownload.download(target, source);
            fail("Expected MikrotikApiException");
        } catch (MikrotikApiException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

    private static void assertNoPublishedOrPartialFile(Path target) {
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(part(target)));
    }

    private static Path part(Path target) {
        return target.resolveSibling(target.getFileName().toString() + ".part");
    }

    private static byte[] payload(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 37);
        }
        return data;
    }
}
