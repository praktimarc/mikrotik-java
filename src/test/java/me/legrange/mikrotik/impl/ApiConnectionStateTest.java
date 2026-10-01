package me.legrange.mikrotik.impl;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import me.legrange.mikrotik.ApiConnection;
import me.legrange.mikrotik.ApiConnectionException;
import me.legrange.mikrotik.ConnectionListener;
import me.legrange.mikrotik.MikrotikApiException;
import me.legrange.mikrotik.ResultListener;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ApiConnectionStateTest {

    @Test
    public void closeIsIdempotentAndDoesNotReportConnectionLoss() throws Exception {
        try (RouterOsTestServer server = idleServer()) {
            ApiConnection connection = connect(server);
            final AtomicInteger losses = new AtomicInteger();
            connection.addConnectionListener(new ConnectionListener() {
                @Override
                public void connectionLost(ApiConnectionException cause) {
                    losses.incrementAndGet();
                }
            });

            connection.close();
            connection.close();

            assertFalse(connection.isConnected());
            assertEquals(0, losses.get());
        }
    }

    @Test
    public void intentionalCloseFailsActiveCommandWithoutLifecycleNotification() throws Exception {
        final CountDownLatch commandSeen = new CountDownLatch(1);
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence command) {
                commandSeen.countDown();
            }
        })) {
            ApiConnection connection = connect(server);
            final AtomicInteger losses = new AtomicInteger();
            connection.addConnectionListener(new ConnectionListener() {
                @Override
                public void connectionLost(ApiConnectionException cause) {
                    losses.incrementAndGet();
                }
            });
            final AtomicReference<MikrotikApiException> error = new AtomicReference<MikrotikApiException>();
            final CountDownLatch failed = new CountDownLatch(1);
            connection.execute("/wait", new NoopResultListener() {
                @Override
                public void error(MikrotikApiException ex) {
                    error.set(ex);
                    failed.countDown();
                }
            });

            assertTrue(commandSeen.await(750, TimeUnit.MILLISECONDS));
            connection.close();
            assertTrue(failed.await(750, TimeUnit.MILLISECONDS));
            assertTrue(error.get() instanceof ApiConnectionException);
            assertEquals(0, losses.get());
        }
    }

    @Test
    public void submissionsAfterCloseFailBeforeWriting() throws Exception {
        try (RouterOsTestServer server = idleServer()) {
            ApiConnection connection = connect(server);
            connection.close();
            final AtomicInteger writes = new AtomicInteger();
            setOutput((ApiConnectionImpl) connection, new DataOutputStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    writes.incrementAndGet();
                    throw new IOException("must not write");
                }
            }));

            try {
                connection.execute("/after/close", new NoopResultListener());
                fail("Expected closed connection failure");
            } catch (ApiConnectionException expected) {
            }
            assertEquals(0, writes.get());
        }
    }

    @Test
    public void fatalSocketLossFailsActiveCommandAndNotifiesLifecycle() throws Exception {
        final CountDownLatch commandSeen = new CountDownLatch(1);
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence command) {
                commandSeen.countDown();
            }
        })) {
            ApiConnection connection = connect(server);
            final AtomicReference<MikrotikApiException> commandError = new AtomicReference<MikrotikApiException>();
            final CountDownLatch commandFailed = new CountDownLatch(1);
            final AtomicReference<ApiConnectionException> lifecycleError = new AtomicReference<ApiConnectionException>();
            final CountDownLatch lifecycleFailed = new CountDownLatch(1);
            connection.addConnectionListener(new ConnectionListener() {
                @Override
                public void connectionLost(ApiConnectionException cause) {
                    lifecycleError.set(cause);
                    lifecycleFailed.countDown();
                }
            });
            connection.execute("/fatal/async", new NoopResultListener() {
                @Override
                public void error(MikrotikApiException ex) {
                    commandError.set(ex);
                    commandFailed.countDown();
                }
            });

            assertTrue(commandSeen.await(750, TimeUnit.MILLISECONDS));
            server.closeClientConnection();
            assertTrue(commandFailed.await(750, TimeUnit.MILLISECONDS));
            assertTrue(lifecycleFailed.await(750, TimeUnit.MILLISECONDS));
            assertTrue(commandError.get() instanceof ApiConnectionException);
            assertTrue(lifecycleError.get() instanceof ApiConnectionException);
            assertFalse(connection.isConnected());
            connection.close();
        }
    }

    @Test
    public void fatalSocketLossTerminatesBinaryDownloadPromptly() throws Exception {
        final CountDownLatch readSeen = new CountDownLatch(1);
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence command) throws Exception {
                if ("/file/print".equals(command.command)) {
                    s.reply("!re", command.tag, "=size=1");
                    s.reply("!done", command.tag);
                } else if ("/file/read".equals(command.command)) {
                    readSeen.countDown();
                }
            }
        })) {
            final ApiConnection connection = connect(server);
            connection.setTimeout(2500);
            final Path dir = Files.createTempDirectory("mikrotik-fatal-binary");
            final Path target = dir.resolve("test.bin");
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Long> download = executor.submit(new Callable<Long>() {
                @Override
                public Long call() throws Exception {
                    return connection.downloadFile("test.bin", target);
                }
            });
            try {
                assertTrue(readSeen.await(1, TimeUnit.SECONDS));
                server.closeClientConnection();
                assertFutureConnectionFailure(download, 750);
                assertFalse(connection.isConnected());
            } finally {
                executor.shutdownNow();
                connection.close();
                Files.deleteIfExists(target);
                Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".part"));
                Files.deleteIfExists(dir);
            }
        }
    }

    @Test
    public void listenerAddedAfterFailureGetsRetainedCauseAndBadListenerDoesNotBlockOthers() throws Exception {
        try (RouterOsTestServer server = idleServer()) {
            ApiConnection connection = connect(server);
            assertTrue(server.awaitClientConnection(1000));
            final AtomicReference<ApiConnectionException> firstCause = new AtomicReference<ApiConnectionException>();
            final CountDownLatch first = new CountDownLatch(1);
            connection.addConnectionListener(new ConnectionListener() {
                @Override
                public void connectionLost(ApiConnectionException cause) {
                    firstCause.set(cause);
                    first.countDown();
                    throw new IllegalStateException("listener failure");
                }
            });
            server.closeClientConnection();
            assertTrue(first.await(750, TimeUnit.MILLISECONDS));

            final AtomicReference<ApiConnectionException> lateCause = new AtomicReference<ApiConnectionException>();
            final CountDownLatch late = new CountDownLatch(1);
            connection.addConnectionListener(new ConnectionListener() {
                @Override
                public void connectionLost(ApiConnectionException cause) {
                    lateCause.set(cause);
                    late.countDown();
                }
            });
            assertTrue(late.await(100, TimeUnit.MILLISECONDS));
            assertSame(firstCause.get(), lateCause.get());
            connection.close();
        }
    }

    private static RouterOsTestServer idleServer() throws Exception {
        return new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence command) {
            }
        });
    }

    private static ApiConnection connect(RouterOsTestServer server) throws Exception {
        ApiConnection connection = ApiConnection.connect(SocketFactory.getDefault(),
                InetAddress.getLoopbackAddress().getHostAddress(), server.getPort(), 1000);
        connection.setTimeout(1500);
        return connection;
    }

    private static void assertFutureConnectionFailure(Future<?> future, long timeoutMs) throws Exception {
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            fail("Expected connection failure");
        } catch (ExecutionException ex) {
            assertTrue(ex.getCause() instanceof ApiConnectionException);
        } catch (TimeoutException ex) {
            fail("Operation did not terminate promptly after connection ended");
        }
    }

    private static void setOutput(ApiConnectionImpl connection, DataOutputStream output) throws Exception {
        Field field = ApiConnectionImpl.class.getDeclaredField("out");
        field.setAccessible(true);
        field.set(connection, output);
    }

    private static class NoopResultListener implements ResultListener {
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
