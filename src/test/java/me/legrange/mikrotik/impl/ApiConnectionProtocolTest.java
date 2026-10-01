package me.legrange.mikrotik.impl;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import me.legrange.mikrotik.ApiConnection;
import me.legrange.mikrotik.ApiCommandException;
import me.legrange.mikrotik.ApiConnectionException;
import me.legrange.mikrotik.ApiDataException;
import me.legrange.mikrotik.MikrotikApiException;
import me.legrange.mikrotik.ResultListener;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ApiConnectionProtocolTest {

    @Test
    public void synchronousEmptyReplyWaitsForDoneAndReturnsNoResults() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> {
            s.reply("!empty", command.tag);
            s.reply("!done", command.tag);
        })) {
            ApiConnection connection = connect(server);
            try {
                assertTrue(connection.execute("/empty/sync").isEmpty());
            } finally {
                connection.close();
            }
        }
    }

    @Test
    public void asynchronousEmptyReplyDoesNotProduceData() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> {
            s.reply("!empty", command.tag);
            s.reply("!done", command.tag);
        })) {
            ApiConnection connection = connect(server);
            try {
                AtomicInteger received = new AtomicInteger();
                CountDownLatch completed = new CountDownLatch(1);
                connection.execute("/empty/async", new NoopResultListener() {
                    @Override
                    public void receive(Map<String, String> result) {
                        received.incrementAndGet();
                    }

                    @Override
                    public void completed() {
                        completed.countDown();
                    }
                });

                assertTrue(completed.await(750, TimeUnit.MILLISECONDS));
                assertEquals(0, received.get());
            } finally {
                connection.close();
            }
        }
    }

    @Test
    public void legacyHaltRemainsACommandError() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) ->
                s.reply("!halt", command.tag, "=message=halted", "=category=2"))) {
            ApiConnection connection = connect(server);
            try {
                AtomicReference<MikrotikApiException> error = new AtomicReference<>();
                CountDownLatch failed = new CountDownLatch(1);
                connection.execute("/legacy/halt", new NoopResultListener() {
                    @Override
                    public void error(MikrotikApiException ex) {
                        error.set(ex);
                        failed.countDown();
                    }
                });

                assertTrue(failed.await(750, TimeUnit.MILLISECONDS));
                assertTrue(error.get() instanceof ApiCommandException);
                assertTrue(connection.isConnected());
            } finally {
                connection.close();
            }
        }
    }

    @Test
    public void fatalReplyFailsSessionAndPreservesDiagnostic() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) ->
                s.replyFatal("session terminated on request"))) {
            ApiConnection connection = connect(server);
            AtomicReference<ApiConnectionException> lifecycleFailure = new AtomicReference<>();
            CountDownLatch lifecycle = new CountDownLatch(1);
            connection.addConnectionListener(cause -> {
                lifecycleFailure.set(cause);
                lifecycle.countDown();
            });
            AtomicReference<MikrotikApiException> commandFailure = new AtomicReference<>();
            CountDownLatch command = new CountDownLatch(1);
            connection.execute("/fatal", new NoopResultListener() {
                @Override
                public void error(MikrotikApiException ex) {
                    commandFailure.set(ex);
                    command.countDown();
                }
            });

            assertTrue(lifecycle.await(750, TimeUnit.MILLISECONDS));
            assertTrue(command.await(750, TimeUnit.MILLISECONDS));
            assertNotNull(lifecycleFailure.get());
            assertTrue(lifecycleFailure.get().getMessage().contains("session terminated on request"));
            assertTrue(commandFailure.get() instanceof ApiConnectionException);
            assertFalse(connection.isConnected());
            connection.close();
        }
    }

    @Test
    public void unknownReplyTypeFailsWholeSession() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) ->
                s.reply("!future-reply", command.tag))) {
            ApiConnection connection = connect(server);
            CountDownLatch lifecycle = new CountDownLatch(1);
            AtomicReference<ApiConnectionException> failure = new AtomicReference<>();
            connection.addConnectionListener(cause -> {
                failure.set(cause);
                lifecycle.countDown();
            });
            connection.execute("/future/reply", new NoopResultListener());

            assertTrue(lifecycle.await(750, TimeUnit.MILLISECONDS));
            assertNotNull(failure.get());
            assertTrue(failure.get().getCause() instanceof ApiDataException);
            assertFalse(connection.isConnected());
            connection.close();
        }
    }

    @Test
    public void malformedTaggedTrapFailsOnlyThatCommand() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) -> {
            if ("/bad/trap".equals(command.command)) {
                s.reply("!trap", command.tag, "=message=bad", "=category=not-a-number");
            } else if ("/good".equals(command.command)) {
                s.reply("!re", command.tag, "=value=ok");
                s.reply("!done", command.tag);
            }
        })) {
            ApiConnection connection = connect(server);
            try {
                AtomicReference<MikrotikApiException> badFailure = new AtomicReference<>();
                CountDownLatch badFailed = new CountDownLatch(1);
                connection.execute("/bad/trap", new NoopResultListener() {
                    @Override
                    public void error(MikrotikApiException ex) {
                        badFailure.set(ex);
                        badFailed.countDown();
                    }
                });

                List<Map<String, String>> good = connection.execute("/good");

                assertTrue(badFailed.await(750, TimeUnit.MILLISECONDS));
                assertTrue(badFailure.get() instanceof ApiDataException);
                assertEquals(1, good.size());
                assertEquals("ok", good.get(0).get("value"));
                assertTrue(connection.isConnected());
            } finally {
                connection.close();
            }
        }
    }

    @Test
    public void duplicateTagsMakeRoutingUntrustworthyAndFailSession() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) ->
                s.replyWords(RouterOsTestServer.text("!done"),
                        RouterOsTestServer.text(".tag=" + command.tag),
                        RouterOsTestServer.text(".tag=other")))) {
            ApiConnection connection = connect(server);
            CountDownLatch lifecycle = new CountDownLatch(1);
            connection.addConnectionListener(cause -> lifecycle.countDown());
            connection.execute("/duplicate/tag", new NoopResultListener());

            assertTrue(lifecycle.await(750, TimeUnit.MILLISECONDS));
            assertFalse(connection.isConnected());
            connection.close();
        }
    }

    @Test
    public void reservedControlByteFailsSession() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer((s, command) ->
                s.writeRawBytes(new byte[]{(byte) 0xf8}))) {
            ApiConnection connection = connect(server);
            CountDownLatch lifecycle = new CountDownLatch(1);
            connection.addConnectionListener(cause -> lifecycle.countDown());
            connection.execute("/reserved/control", new NoopResultListener());

            assertTrue(lifecycle.await(750, TimeUnit.MILLISECONDS));
            assertFalse(connection.isConnected());
            connection.close();
        }
    }

    private static ApiConnection connect(RouterOsTestServer server) throws Exception {
        ApiConnection connection = ApiConnection.connect(SocketFactory.getDefault(),
                InetAddress.getLoopbackAddress().getHostAddress(), server.getPort(), 1000);
        connection.setTimeout(1500);
        return connection;
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
