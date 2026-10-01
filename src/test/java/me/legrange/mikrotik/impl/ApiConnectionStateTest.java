package me.legrange.mikrotik.impl;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    public void closeIsIdempotentAndMarksConnectionDisconnected() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> { })) {
            ApiConnection connection = connect(server);
            assertTrue(connection.isConnected());

            connection.close();
            connection.close();

            assertFalse(connection.isConnected());
        }
    }

    @Test
    public void intentionalCloseFailsActiveCommandsButDoesNotNotifyConnectionLossListener() throws Exception {
        CountDownLatch commandsSeen = new CountDownLatch(2);
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> commandsSeen.countDown())) {
            ApiConnection connection = connect(server);
            connection.setTimeout(2500);
            AtomicInteger lifecycleLosses = new AtomicInteger();
            connection.addConnectionListener(cause -> lifecycleLosses.incrementAndGet());
            AtomicReference<MikrotikApiException> asyncError = new AtomicReference<>();
            CountDownLatch asyncFailed = new CountDownLatch(1);
            connection.execute("/wait/async", new NoopResultListener() {
                @Override
                public void error(MikrotikApiException ex) {
                    asyncError.set(ex);
                    asyncFailed.countDown();
                }
            });

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<List<Map<String, String>>> sync = executor.submit(() -> connection.execute("/wait/sync"));
            try {
                assertTrue(commandsSeen.await(1, TimeUnit.SECONDS));
                connection.close();

                assertTrue(asyncFailed.await(750, TimeUnit.MILLISECONDS));
                assertTrue(asyncError.get() instanceof ApiConnectionException);
                assertFutureConnectionFailure(sync, 750);
                assertEquals(0, lifecycleLosses.get());
                assertFalse(connection.isConnected());
            } finally {
                executor.shutdownNow();
                connection.close();
            }
        }
    }

    @Test
    public void submissionsAfterCloseFailBeforeTouchingOutput() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> { })) {
            ApiConnection connection = connect(server);
            connection.close();
            AtomicInteger writes = new AtomicInteger();
            DataOutputStream tracking = new DataOutputStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    writes.incrementAndGet();
                    throw new IOException("output must not be touched");
                }
            });
            setOutput((ApiConnectionImpl) connection, tracking);

            try {
                connection.execute("/after/close", new NoopResultListener());
                fail("Expected closed text submission to fail");
            } catch (ApiConnectionException expected) {
            }
            assertEquals(0, writes.get());

            Throwable binaryFailure = invokeBinaryExecute((ApiConnectionImpl) connection,
                    new Command("/after/close/binary"), 25);
            assertTrue(binaryFailure instanceof ApiConnectionException);
            assertEquals(0, writes.get());
        }
    }

    @Test
    public void fatalSocketLossFailsAllActiveTextCommandsAndDrainsRegistries() throws Exception {
        CountDownLatch commandsSeen = new CountDownLatch(2);
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> commandsSeen.countDown())) {
            ApiConnection connection = connect(server);
            connection.setTimeout(2500);
            AtomicReference<MikrotikApiException> asyncError = new AtomicReference<>();
            CountDownLatch asyncFailed = new CountDownLatch(1);
            connection.execute("/fatal/async", new NoopResultListener() {
                @Override
                public void error(MikrotikApiException ex) {
                    asyncError.set(ex);
                    asyncFailed.countDown();
                }
            });

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<List<Map<String, String>>> sync = executor.submit(() -> connection.execute("/fatal/sync"));
            try {
                assertTrue(commandsSeen.await(1, TimeUnit.SECONDS));
                server.closeClientConnection();

                assertTrue(asyncFailed.await(750, TimeUnit.MILLISECONDS));
                assertTrue(asyncError.get() instanceof ApiConnectionException);
                assertFutureConnectionFailure(sync, 750);
                assertTrue(waitUntil(() -> !connection.isConnected(), 750));
                assertTrue(textListeners((ApiConnectionImpl) connection).isEmpty());
                assertTrue(binaryListeners((ApiConnectionImpl) connection).isEmpty());
            } finally {
                executor.shutdownNow();
                connection.close();
            }
        }
    }

    @Test
    public void fatalSocketLossFailsActiveBinaryDownloadPromptly() throws Exception {
        CountDownLatch readSeen = new CountDownLatch(1);
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> {
            if ("/file/print".equals(command.command)) {
                s.reply("!re", command.tag, "=size=1");
                s.reply("!done", command.tag);
            } else if ("/file/read".equals(command.command)) {
                readSeen.countDown();
            }
        })) {
            ApiConnection connection = connect(server);
            connection.setTimeout(2500);
            Path dir = Files.createTempDirectory("mikrotik-fatal-binary");
            Path target = dir.resolve("test.bin");
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Long> download = executor.submit(() -> connection.downloadFile("test.bin", target));
            try {
                assertTrue(readSeen.await(1, TimeUnit.SECONDS));
                server.closeClientConnection();
                assertFutureConnectionFailure(download, 750);
                assertTrue(waitUntil(() -> !connection.isConnected(), 750));
                assertTrue(binaryListeners((ApiConnectionImpl) connection).isEmpty());
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
    public void idleFatalLossNotifiesConnectionListenerExactlyOnceEvenWithDuplicateRegistration() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> { })) {
            ApiConnection connection = connect(server);
            assertTrue(server.awaitClientConnection(1000));
            AtomicInteger notifications = new AtomicInteger();
            CountDownLatch notified = new CountDownLatch(1);
            ConnectionListener listener = cause -> {
                notifications.incrementAndGet();
                notified.countDown();
            };
            connection.addConnectionListener(listener);
            connection.addConnectionListener(listener);

            server.closeClientConnection();

            assertTrue(notified.await(750, TimeUnit.MILLISECONDS));
            assertTrue(waitUntil(() -> notifications.get() == 1, 250));
            assertEquals(1, notifications.get());
            assertFalse(connection.isConnected());
            connection.close();
        }
    }

    @Test
    public void removedConnectionListenerIsNotNotifiedAndRemovalIsIdempotent() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> { })) {
            ApiConnection connection = connect(server);
            assertTrue(server.awaitClientConnection(1000));
            AtomicInteger removedNotifications = new AtomicInteger();
            ConnectionListener removed = cause -> removedNotifications.incrementAndGet();
            connection.addConnectionListener(removed);
            connection.removeConnectionListener(removed);
            connection.removeConnectionListener(removed);
            CountDownLatch witness = new CountDownLatch(1);
            connection.addConnectionListener(cause -> witness.countDown());

            server.closeClientConnection();

            assertTrue(witness.await(750, TimeUnit.MILLISECONDS));
            assertEquals(0, removedNotifications.get());
            connection.close();
        }
    }

    @Test
    public void addingListenerAfterFailureNotifiesImmediatelyWithRetainedCause() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> { })) {
            ApiConnection connection = connect(server);
            assertTrue(server.awaitClientConnection(1000));
            AtomicReference<ApiConnectionException> firstCause = new AtomicReference<>();
            CountDownLatch first = new CountDownLatch(1);
            connection.addConnectionListener(cause -> {
                firstCause.set(cause);
                first.countDown();
            });
            server.closeClientConnection();
            assertTrue(first.await(750, TimeUnit.MILLISECONDS));

            AtomicReference<ApiConnectionException> lateCause = new AtomicReference<>();
            CountDownLatch late = new CountDownLatch(1);
            connection.addConnectionListener(cause -> {
                lateCause.set(cause);
                late.countDown();
            });

            assertTrue(late.await(100, TimeUnit.MILLISECONDS));
            assertSame(firstCause.get(), lateCause.get());
            connection.close();
        }
    }

    @Test
    public void addingListenerAfterIntentionalCloseDoesNotNotify() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> { })) {
            ApiConnection connection = connect(server);
            connection.close();
            AtomicInteger notifications = new AtomicInteger();

            connection.addConnectionListener(cause -> notifications.incrementAndGet());

            assertEquals(0, notifications.get());
        }
    }

    @Test
    public void throwingConnectionListenerDoesNotPreventRemainingListeners() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> { })) {
            ApiConnection connection = connect(server);
            assertTrue(server.awaitClientConnection(1000));
            CountDownLatch healthyListener = new CountDownLatch(1);
            connection.addConnectionListener(cause -> {
                throw new IllegalStateException("listener failure");
            });
            connection.addConnectionListener(cause -> healthyListener.countDown());

            server.closeClientConnection();

            assertTrue(healthyListener.await(750, TimeUnit.MILLISECONDS));
            connection.close();
        }
    }

    private static ApiConnection connect(RouterOsTestServer server) throws Exception {
        return ApiConnection.connect(SocketFactory.getDefault(),
                InetAddress.getLoopbackAddress().getHostAddress(), server.getPort(), 1000);
    }

    private static void assertFutureConnectionFailure(Future<?> future, long timeoutMs) throws Exception {
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            fail("Expected connection failure");
        } catch (ExecutionException ex) {
            assertTrue("Expected ApiConnectionException but got " + ex.getCause(),
                    ex.getCause() instanceof ApiConnectionException);
        } catch (TimeoutException ex) {
            fail("Operation did not terminate promptly after connection ended");
        }
    }

    private static boolean waitUntil(Check check, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (check.get()) {
                return true;
            }
            Thread.sleep(5);
        }
        return check.get();
    }

    private static Throwable invokeBinaryExecute(ApiConnectionImpl connection, Command command, int timeout) throws Exception {
        Method method = ApiConnectionImpl.class.getDeclaredMethod("executeBinaryRead", Command.class, Integer.TYPE);
        method.setAccessible(true);
        try {
            method.invoke(connection, command, timeout);
            fail("Expected binary submission to fail");
            return null;
        } catch (InvocationTargetException ex) {
            return ex.getCause();
        }
    }

    private static void setOutput(ApiConnectionImpl connection, DataOutputStream output) throws Exception {
        Field field = ApiConnectionImpl.class.getDeclaredField("out");
        field.setAccessible(true);
        field.set(connection, output);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ResultListener> textListeners(ApiConnectionImpl connection) throws Exception {
        Field field = ApiConnectionImpl.class.getDeclaredField("listeners");
        field.setAccessible(true);
        return (Map<String, ResultListener>) field.get(connection);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, BinaryResultListener> binaryListeners(ApiConnectionImpl connection) throws Exception {
        Field field = ApiConnectionImpl.class.getDeclaredField("binaryListeners");
        field.setAccessible(true);
        return (Map<String, BinaryResultListener>) field.get(connection);
    }

    private interface Check {
        boolean get() throws Exception;
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
