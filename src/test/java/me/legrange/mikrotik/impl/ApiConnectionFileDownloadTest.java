package me.legrange.mikrotik.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import me.legrange.mikrotik.ApiConnection;
import me.legrange.mikrotik.MikrotikApiException;
import me.legrange.mikrotik.ResultListener;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ApiConnectionFileDownloadTest {

    @Test
    public void binaryDownloadIsByteExactAndPreservesFilenameWords() throws Exception {
        final byte[] file = hostilePayload(70013);
        final String remote = "flash/docsis/cm profile=1;#.cfg";
        final AtomicReference<String> query = new AtomicReference<String>();
        final List<String> fileParameters = new ArrayList<String>();
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence c) throws Exception {
                if ("/file/print".equals(c.command)) {
                    query.set(c.queries.isEmpty() ? null : c.queries.get(0));
                    s.reply("!re", c.tag, "=size=" + file.length);
                    s.reply("!done", c.tag);
                } else if ("/file/read".equals(c.command)) {
                    fileParameters.add(c.parameter("file"));
                    int offset = Integer.parseInt(c.parameter("offset"));
                    int chunk = Integer.parseInt(c.parameter("chunk-size"));
                    int length = Math.min(chunk, file.length - offset);
                    s.replyData(c.tag, Arrays.copyOfRange(file, offset, offset + length));
                    s.reply("!done", c.tag);
                } else {
                    throw new AssertionError("Unexpected command " + c.command);
                }
            }
        })) {
            ApiConnection con = connect(server);
            Path dir = Files.createTempDirectory("api-download");
            Path target = dir.resolve("docsis.bin");
            try {
                long bytes = con.downloadFile(remote, target);
                assertEquals(file.length, bytes);
                assertArrayEquals(file, Files.readAllBytes(target));
                assertEquals("?name=" + remote, query.get());
                assertFalse(fileParameters.isEmpty());
                for (String value : fileParameters) {
                    assertEquals(remote, value);
                }
            } finally {
                con.close();
                Files.deleteIfExists(target);
                Files.deleteIfExists(dir);
            }
        }
    }

    @Test
    public void failedDownloadDoesNotPublishPartialFile() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence c) throws Exception {
                if ("/file/print".equals(c.command)) {
                    s.reply("!re", c.tag, "=size=1");
                    s.reply("!done", c.tag);
                } else if ("/file/read".equals(c.command)) {
                    s.reply("!trap", c.tag, "=message=read denied", "=category=5");
                }
            }
        })) {
            ApiConnection con = connect(server);
            Path dir = Files.createTempDirectory("api-trap");
            Path target = dir.resolve("bad.bin");
            try {
                Files.write(target, new byte[]{9});
                expectDownloadFailure(con, "flash/bad.bin", target);
                assertFalse(Files.exists(target));
                assertFalse(Files.exists(target.resolveSibling("bad.bin.part")));
            } finally {
                con.close();
                Files.deleteIfExists(target);
                Files.deleteIfExists(target.resolveSibling("bad.bin.part"));
                Files.deleteIfExists(dir);
            }
        }
    }

    @Test
    public void zeroByteFileSkipsBinaryRead() throws Exception {
        final int[] reads = {0};
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence c) throws Exception {
                if ("/file/print".equals(c.command)) {
                    s.reply("!re", c.tag, "=size=0");
                    s.reply("!done", c.tag);
                } else if ("/file/read".equals(c.command)) {
                    reads[0]++;
                }
            }
        })) {
            ApiConnection con = connect(server);
            Path dir = Files.createTempDirectory("api-zero");
            Path target = dir.resolve("zero.bin");
            try {
                assertEquals(0, con.downloadFile("flash/zero.bin", target));
                assertEquals(0, reads[0]);
                assertEquals(0, Files.size(target));
            } finally {
                con.close();
                Files.deleteIfExists(target);
                Files.deleteIfExists(dir);
            }
        }
    }

    @Test
    public void textAndBinaryRepliesRemainSeparatedByTag() throws Exception {
        final byte[] file = hostilePayload(40000);
        final AtomicReference<String> watchTag = new AtomicReference<String>();
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence c) throws Exception {
                if ("/watch".equals(c.command)) {
                    watchTag.set(c.tag);
                } else if ("/file/print".equals(c.command)) {
                    s.reply("!re", c.tag, "=size=" + file.length);
                    s.reply("!done", c.tag);
                } else if ("/file/read".equals(c.command)) {
                    String textTag = watchTag.getAndSet(null);
                    if (textTag != null) {
                        s.reply("!re", textTag, "=name=text-only");
                        s.reply("!done", textTag);
                    }
                    int offset = Integer.parseInt(c.parameter("offset"));
                    int chunk = Integer.parseInt(c.parameter("chunk-size"));
                    int length = Math.min(chunk, file.length - offset);
                    s.replyData(c.tag, Arrays.copyOfRange(file, offset, offset + length));
                    s.reply("!done", c.tag);
                }
            }
        })) {
            ApiConnection con = connect(server);
            final CountDownLatch textDone = new CountDownLatch(1);
            final AtomicReference<String> textValue = new AtomicReference<String>();
            con.execute("/watch", new ResultListener() {
                @Override
                public void receive(Map<String, String> row) {
                    textValue.set(row.get("name"));
                }

                @Override
                public void error(MikrotikApiException ex) {
                    throw new AssertionError(ex);
                }

                @Override
                public void completed() {
                    textDone.countDown();
                }
            });
            Path dir = Files.createTempDirectory("api-interleave");
            Path target = dir.resolve("data.bin");
            try {
                con.downloadFile("flash/data.bin", target);
                assertTrue(textDone.await(1, TimeUnit.SECONDS));
                assertEquals("text-only", textValue.get());
                assertArrayEquals(file, Files.readAllBytes(target));
            } finally {
                con.close();
                Files.deleteIfExists(target);
                Files.deleteIfExists(dir);
            }
        }
    }

    private static ApiConnection connect(RouterOsTestServer server) throws Exception {
        ApiConnection con = ApiConnection.connect(SocketFactory.getDefault(), "127.0.0.1", server.getPort(), 1000);
        con.setTimeout(1000);
        return con;
    }

    private static void expectDownloadFailure(ApiConnection con, String remote, Path target) throws Exception {
        try {
            con.downloadFile(remote, target);
            fail("Expected download failure");
        } catch (MikrotikApiException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

    private static byte[] hostilePayload(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        byte[] hostile = new byte[]{0, 13, 10, (byte) 0xc0, (byte) 0xaf, (byte) 0xff, (byte) 0xfe,
            '=', 'd', 'a', 't', 'a', '=', '!', 'r', 'e', '!', 'd', 'o', 'n', 'e'};
        System.arraycopy(hostile, 0, data, 123, hostile.length);
        return data;
    }
}
