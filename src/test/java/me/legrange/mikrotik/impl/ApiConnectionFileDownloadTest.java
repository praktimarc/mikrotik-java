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
        byte[] file = hostilePayload(70013);
        String remote = "flash/docsis/cm profile=1;#.cfg";
        AtomicReference<String> query = new AtomicReference<>();
        List<String> fileParameters = new ArrayList<>();
        try (RouterOsTestServer server = new RouterOsTestServer((s, c) -> {
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
        })) {
            ApiConnection con = connect(server);
            Path target = Files.createTempDirectory("api-download").resolve("docsis.bin");

            long bytes = con.downloadFile(remote, target);

            assertEquals(file.length, bytes);
            assertArrayEquals(file, Files.readAllBytes(target));
            assertEquals("?name=" + remote, query.get());
            assertFalse(fileParameters.isEmpty());
            for (String value : fileParameters) {
                assertEquals(remote, value);
            }
            con.close();
        }
    }

    @Test
    public void zeroByteFileSkipsFileRead() throws Exception {
        int[] reads = {0};
        try (RouterOsTestServer server = new RouterOsTestServer((s, c) -> {
            if ("/file/print".equals(c.command)) {
                s.reply("!re", c.tag, "=size=0");
                s.reply("!done", c.tag);
            } else if ("/file/read".equals(c.command)) {
                reads[0]++;
                throw new AssertionError("file/read called for zero-byte file");
            }
        })) {
            ApiConnection con = connect(server);
            Path target = Files.createTempDirectory("api-zero").resolve("zero.bin");

            assertEquals(0, con.downloadFile("flash/zero.bin", target));
            assertEquals(0, reads[0]);
            assertEquals(0, Files.size(target));
            con.close();
        }
    }

    @Test
    public void missingRemoteFileRemovesOldTarget() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, c) -> {
            if ("/file/print".equals(c.command)) {
                s.reply("!done", c.tag);
            }
        })) {
            ApiConnection con = connect(server);
            Path target = Files.createTempDirectory("api-missing").resolve("old.bin");
            Files.write(target, new byte[]{9});

            expectDownloadFailure(con, "flash/missing.bin", target);

            assertFalse(Files.exists(target));
            con.close();
        }
    }

    @Test
    public void malformedAndNegativeSizesFail() throws Exception {
        for (String size : new String[]{"abc", "-1"}) {
            try (RouterOsTestServer server = new RouterOsTestServer((s, c) -> {
                if ("/file/print".equals(c.command)) {
                    s.reply("!re", c.tag, "=size=" + size);
                    s.reply("!done", c.tag);
                }
            })) {
                ApiConnection con = connect(server);
                Path target = Files.createTempDirectory("api-size").resolve("bad.bin");

                expectDownloadFailure(con, "flash/bad.bin", target);

                assertFalse(Files.exists(target));
                con.close();
            }
        }
    }

    @Test
    public void missingAndDuplicateDataFailAndClean() throws Exception {
        for (boolean duplicate : new boolean[]{false, true}) {
            try (RouterOsTestServer server = new RouterOsTestServer((s, c) -> {
                if ("/file/print".equals(c.command)) {
                    s.reply("!re", c.tag, "=size=1");
                    s.reply("!done", c.tag);
                } else if ("/file/read".equals(c.command)) {
                    if (duplicate) {
                        s.replyRaw(c.tag, RouterOsTestServer.text("=data=a"), RouterOsTestServer.text("=data=b"));
                    } else {
                        s.reply("!re", c.tag, "=other=x");
                    }
                    s.reply("!done", c.tag);
                }
            })) {
                ApiConnection con = connect(server);
                Path target = Files.createTempDirectory("api-data").resolve("bad.bin");

                expectDownloadFailure(con, "flash/bad.bin", target);

                assertFalse(Files.exists(target));
                assertFalse(Files.exists(target.resolveSibling("bad.bin.part")));
                con.close();
            }
        }
    }

    @Test
    public void routerTrapFailsAndCleans() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, c) -> {
            if ("/file/print".equals(c.command)) {
                s.reply("!re", c.tag, "=size=1");
                s.reply("!done", c.tag);
            } else if ("/file/read".equals(c.command)) {
                s.reply("!trap", c.tag, "=message=read denied", "=category=5");
            }
        })) {
            ApiConnection con = connect(server);
            Path target = Files.createTempDirectory("api-trap").resolve("bad.bin");

            expectDownloadFailure(con, "flash/bad.bin", target);

            assertFalse(Files.exists(target));
            con.close();
        }
    }

    @Test
    public void textApiBehaviorRemainsCompatible() throws Exception {
        AtomicReference<String> canceled = new AtomicReference<>();
        try (RouterOsTestServer server = new RouterOsTestServer((s, c) -> {
            switch (c.command) {
                case "/login":
                    s.reply("!done", c.tag);
                    break;
                case "/system/resource/print":
                    s.reply("!re", c.tag, "=name=router-a");
                    s.reply("!done", c.tag);
                    break;
                case "/interface/print":
                    s.reply("!re", c.tag, "=name=ether1");
                    s.reply("!done", c.tag);
                    break;
                case "/bad":
                    s.reply("!trap", c.tag, "=message=bad command", "=category=5");
                    break;
                case "/monitor":
                    break;
                case "/cancel":
                    canceled.set(c.parameter("tag"));
                    s.reply("!done", c.tag);
                    break;
                default:
                    throw new AssertionError("Unexpected command " + c.command);
            }
        })) {
            ApiConnection con = connect(server);
            con.login("admin", "secret");

            List<Map<String, String>> result = con.execute("/system/resource/print");
            assertEquals(1, result.size());
            assertEquals("router-a", result.get(0).get("name"));

            CountDownLatch asyncDone = new CountDownLatch(1);
            AtomicReference<String> asyncName = new AtomicReference<>();
            con.execute("/interface/print", new ResultListener() {
                @Override
                public void receive(Map<String, String> row) {
                    asyncName.set(row.get("name"));
                }

                @Override
                public void error(MikrotikApiException ex) {
                    throw new AssertionError(ex);
                }

                @Override
                public void completed() {
                    asyncDone.countDown();
                }
            });
            assertTrue(asyncDone.await(1, TimeUnit.SECONDS));
            assertEquals("ether1", asyncName.get());

            try {
                con.execute("/bad");
                fail("Expected trap");
            } catch (MikrotikApiException expected) {
                assertEquals("bad command", expected.getMessage());
            }

            String monitorTag = con.execute("/monitor", new NoopListener());
            con.cancel(monitorTag);
            assertEquals(monitorTag, canceled.get());
            con.close();
            assertFalse(con.isConnected());
        }
    }

    @Test
    public void interleavedTextAndBinaryTagsStaySeparated() throws Exception {
        byte[] file = hostilePayload(40000);
        AtomicReference<String> watchTag = new AtomicReference<>();
        try (RouterOsTestServer server = new RouterOsTestServer((s, c) -> {
            if ("/watch".equals(c.command)) {
                watchTag.set(c.tag);
            } else if ("/file/print".equals(c.command)) {
                s.reply("!re", c.tag, "=size=" + file.length);
                s.reply("!done", c.tag);
            } else if ("/file/read".equals(c.command)) {
                String textTag = watchTag.get();
                if (textTag != null) {
                    s.reply("!re", textTag, "=name=text-only");
                    s.reply("!done", textTag);
                    watchTag.set(null);
                }
                int offset = Integer.parseInt(c.parameter("offset"));
                int chunk = Integer.parseInt(c.parameter("chunk-size"));
                int length = Math.min(chunk, file.length - offset);
                s.replyData(c.tag, Arrays.copyOfRange(file, offset, offset + length));
                s.reply("!done", c.tag);
            }
        })) {
            ApiConnection con = connect(server);
            CountDownLatch textDone = new CountDownLatch(1);
            AtomicReference<String> textValue = new AtomicReference<>();
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
            Path target = Files.createTempDirectory("api-interleave").resolve("data.bin");

            con.downloadFile("flash/data.bin", target);

            assertTrue(textDone.await(1, TimeUnit.SECONDS));
            assertEquals("text-only", textValue.get());
            assertArrayEquals(file, Files.readAllBytes(target));
            con.close();
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

    private static final class NoopListener implements ResultListener {
        @Override
        public void receive(Map<String, String> result) {
        }

        @Override
        public void error(MikrotikApiException ex) {
        }

        @Override
        public void completed() {
        }
    }
}
