package me.legrange.mikrotik.impl;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ApiConnectionWriteConcurrencyTest {

    @Test(timeout = 3000)
    public void concurrentTextCommandsWriteWholeSentencesAtomically() throws Exception {
        final CountDownLatch secondCallReady = new CountDownLatch(1);
        CoordinatedDataOutputStream output = new CoordinatedDataOutputStream(secondCallReady);
        final ApiConnectionImpl connection = newConnectedConnection(output);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(new Runnable() {
                @Override
                public void run() {
                    executeAsync(connection, "/first value=alpha");
                }
            });
            assertTrue(output.firstWriteEntered.await(500, TimeUnit.MILLISECONDS));
            Future<?> second = executor.submit(new Runnable() {
                @Override
                public void run() {
                    secondCallReady.countDown();
                    executeAsync(connection, "/second value=beta");
                }
            });

            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
            assertFalse("Complete RouterOS command sentences must not interleave", output.interleaved.get());
        } finally {
            executor.shutdownNow();
            connection.close();
        }
    }

    @Test(timeout = 3000)
    public void textAndBinaryCommandsUseTheSameWriteLock() throws Exception {
        final CountDownLatch secondCallReady = new CountDownLatch(1);
        CoordinatedDataOutputStream output = new CoordinatedDataOutputStream(secondCallReady);
        final ApiConnectionImpl connection = newConnectedConnection(output);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> text = executor.submit(new Runnable() {
                @Override
                public void run() {
                    executeAsync(connection, "/text value=alpha");
                }
            });
            assertTrue(output.firstWriteEntered.await(500, TimeUnit.MILLISECONDS));
            Future<?> binary = executor.submit(new Runnable() {
                @Override
                public void run() {
                    secondCallReady.countDown();
                    Throwable failure = invokeBinaryExecute(connection, new Command("/file/read"), 40);
                    if (!(failure instanceof ApiConnectionException)
                            || failure.getMessage() == null
                            || !failure.getMessage().contains("timed out")) {
                        throw new AssertionError("Expected binary read timeout after its command was written", failure);
                    }
                }
            });

            text.get(1, TimeUnit.SECONDS);
            binary.get(1, TimeUnit.SECONDS);
            assertFalse("Text and binary command sentences must share one write lock", output.interleaved.get());
        } finally {
            executor.shutdownNow();
            connection.close();
        }
    }

    @Test(timeout = 3000)
    public void sendIOExceptionFailsExistingCommandAndSession() throws Exception {
        final CountDownLatch holdSeen = new CountDownLatch(1);
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence command) {
                if ("/hold".equals(command.command)) {
                    holdSeen.countDown();
                }
            }
        })) {
            ApiConnection connection = connect(server);
            final AtomicReference<MikrotikApiException> heldFailure = new AtomicReference<MikrotikApiException>();
            final CountDownLatch heldFailed = new CountDownLatch(1);
            connection.execute("/hold", new NoopResultListener() {
                @Override
                public void error(MikrotikApiException ex) {
                    heldFailure.set(ex);
                    heldFailed.countDown();
                }
            });
            assertTrue(holdSeen.await(750, TimeUnit.MILLISECONDS));

            final CountDownLatch connectionLost = new CountDownLatch(1);
            connection.addConnectionListener(new ConnectionListener() {
                @Override
                public void connectionLost(ApiConnectionException cause) {
                    connectionLost.countDown();
                }
            });
            setOutput((ApiConnectionImpl) connection, new DataOutputStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("deterministic send failure");
                }
            }));

            try {
                connection.execute("/boom", new NoopResultListener());
                fail("Expected send failure");
            } catch (ApiConnectionException expected) {
                assertTrue(expected.getMessage().contains("deterministic send failure"));
            }

            assertTrue(heldFailed.await(750, TimeUnit.MILLISECONDS));
            assertTrue(heldFailure.get() instanceof ApiConnectionException);
            assertTrue(connectionLost.await(750, TimeUnit.MILLISECONDS));
            assertFalse(connection.isConnected());
            connection.close();
        }
    }

    @Test(timeout = 3000)
    public void sendFailureBecomesTerminalBeforeAnotherWriteStarts() throws Exception {
        CountingFailingOutputStream failing = new CountingFailingOutputStream();
        ApiConnectionImpl connection = newConnectedConnection(new DataOutputStream(failing));
        try {
            Throwable first = invokeWriteCommand(connection, new Command("/first/failing"));
            assertTrue(first instanceof ApiConnectionException);
            assertFalse(connection.isConnected());
            assertEquals(1, failing.writeAttempts);

            Throwable second = invokeWriteCommand(connection, new Command("/second/must-not-write"));
            assertTrue(second instanceof ApiConnectionException);
            assertEquals(1, failing.writeAttempts);
        } finally {
            connection.close();
        }
    }

    private static ApiConnection connect(RouterOsTestServer server) throws Exception {
        return ApiConnection.connect(SocketFactory.getDefault(),
                InetAddress.getLoopbackAddress().getHostAddress(), server.getPort(), 1000);
    }

    private static void executeAsync(ApiConnection connection, String command) {
        try {
            connection.execute(command, new NoopResultListener());
        } catch (MikrotikApiException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Throwable invokeBinaryExecute(ApiConnectionImpl connection, Command command, int timeout) {
        try {
            Method method = ApiConnectionImpl.class.getDeclaredMethod("executeBinaryRead", Command.class, Integer.TYPE);
            method.setAccessible(true);
            try {
                method.invoke(connection, command, timeout);
                throw new AssertionError("Expected binary read timeout");
            } catch (InvocationTargetException ex) {
                return ex.getCause();
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Throwable invokeWriteCommand(ApiConnectionImpl connection, Command command) throws Exception {
        Method method = ApiConnectionImpl.class.getDeclaredMethod("writeCommand", Command.class);
        method.setAccessible(true);
        try {
            method.invoke(connection, command);
            fail("Expected command write failure");
            return null;
        } catch (InvocationTargetException ex) {
            return ex.getCause();
        }
    }

    private static ApiConnectionImpl newConnectedConnection(DataOutputStream output) throws Exception {
        Constructor<ApiConnectionImpl> constructor = ApiConnectionImpl.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ApiConnectionImpl connection = constructor.newInstance();
        Field state = ApiConnectionImpl.class.getDeclaredField("state");
        state.setAccessible(true);
        for (Object constant : state.getType().getEnumConstants()) {
            if ("CONNECTED".equals(constant.toString())) {
                state.set(connection, constant);
                setOutput(connection, output);
                return connection;
            }
        }
        throw new AssertionError("CONNECTED state constant not found");
    }

    private static void setOutput(ApiConnectionImpl connection, DataOutputStream output) throws Exception {
        Field field = ApiConnectionImpl.class.getDeclaredField("out");
        field.setAccessible(true);
        field.set(connection, output);
    }

    private static final class CountingFailingOutputStream extends OutputStream {
        private int writeAttempts;

        @Override
        public void write(int value) throws IOException {
            writeAttempts++;
            throw new IOException("deterministic send failure");
        }
    }

    private static final class CoordinatedDataOutputStream extends DataOutputStream {
        private final CountDownLatch secondCallReady;
        private final CountDownLatch secondWriterObserved = new CountDownLatch(1);
        private final AtomicReference<Thread> sentenceOwner = new AtomicReference<Thread>();
        private final AtomicBoolean gateUsed = new AtomicBoolean(false);
        private final AtomicBoolean interleaved = new AtomicBoolean(false);
        private final CountDownLatch firstWriteEntered = new CountDownLatch(1);

        private CoordinatedDataOutputStream(CountDownLatch secondCallReady) {
            super(new ByteArrayOutputStream());
            this.secondCallReady = secondCallReady;
        }

        @Override
        public void write(int value) throws IOException {
            observeByte(value & 0xff);
            super.write(value);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            for (int i = offset; i < offset + length; i++) {
                observeByte(data[i] & 0xff);
            }
            super.write(data, offset, length);
        }

        private void observeByte(int value) throws IOException {
            Thread current = Thread.currentThread();
            Thread owner = sentenceOwner.get();
            if (owner == null) {
                if (sentenceOwner.compareAndSet(null, current)) {
                    owner = current;
                    if (gateUsed.compareAndSet(false, true)) {
                        firstWriteEntered.countDown();
                        await(secondCallReady, 500);
                        await(secondWriterObserved, 200);
                    }
                } else {
                    owner = sentenceOwner.get();
                }
            }
            if (owner != null && owner != current) {
                interleaved.set(true);
                secondWriterObserved.countDown();
            }
            if (value == 0 && sentenceOwner.get() == current) {
                sentenceOwner.compareAndSet(current, null);
            }
        }

        private static void await(CountDownLatch latch, long timeoutMs) throws IOException {
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while coordinating concurrent writes", ex);
            }
        }
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
