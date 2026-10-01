package me.legrange.mikrotik.impl;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import me.legrange.mikrotik.ApiCommandException;
import me.legrange.mikrotik.ApiConnection;
import me.legrange.mikrotik.ApiConnectionException;
import me.legrange.mikrotik.ApiDataException;
import me.legrange.mikrotik.ConnectionListener;
import me.legrange.mikrotik.MikrotikApiException;
import me.legrange.mikrotik.ResultListener;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ApiConnectionProtocolTest {

    @Test
    public void emptyReplyWaitsForDoneAndProducesNoData() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence command) throws Exception {
                s.reply("!empty", command.tag);
                s.reply("!done", command.tag);
            }
        })) {
            ApiConnection connection = connect(server);
            try {
                assertTrue(connection.execute("/empty/sync").isEmpty());

                final AtomicInteger received = new AtomicInteger();
                final CountDownLatch completed = new CountDownLatch(1);
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
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence command) throws Exception {
                s.reply("!halt", command.tag, "=message=halted", "=category=2");
            }
        })) {
            ApiConnection connection = connect(server);
            try {
                final AtomicReference<MikrotikApiException> error = new AtomicReference<MikrotikApiException>();
                final CountDownLatch failed = new CountDownLatch(1);
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
    public void fatalReplyFailsWholeSessionAndPreservesDiagnostic() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence command) throws Exception {
                s.replyFatal("session terminated on request");
            }
        })) {
            ApiConnection connection = connect(server);
            final AtomicReference<ApiConnectionException> lifecycleFailure = new AtomicReference<ApiConnectionException>();
            final CountDownLatch lifecycle = new CountDownLatch(1);
            connection.addConnectionListener(new ConnectionListener() {
                @Override
                public void connectionLost(ApiConnectionException cause) {
                    lifecycleFailure.set(cause);
                    lifecycle.countDown();
                }
            });
            final AtomicReference<MikrotikApiException> commandFailure = new AtomicReference<MikrotikApiException>();
            final CountDownLatch command = new CountDownLatch(1);
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
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence command) throws Exception {
                s.reply("!future-reply", command.tag);
            }
        })) {
            ApiConnection connection = connect(server);
            final CountDownLatch lifecycle = new CountDownLatch(1);
            final AtomicReference<ApiConnectionException> failure = new AtomicReference<ApiConnectionException>();
            connection.addConnectionListener(new ConnectionListener() {
                @Override
                public void connectionLost(ApiConnectionException cause) {
                    failure.set(cause);
                    lifecycle.countDown();
                }
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
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence command) throws Exception {
                if ("/bad/trap".equals(command.command)) {
                    s.reply("!trap", command.tag, "=message=bad", "=category=not-a-number");
                } else if ("/good".equals(command.command)) {
                    s.reply("!re", command.tag, "=value=ok");
                    s.reply("!done", command.tag);
                }
            }
        })) {
            ApiConnection connection = connect(server);
            try {
                final AtomicReference<MikrotikApiException> badFailure = new AtomicReference<MikrotikApiException>();
                final CountDownLatch badFailed = new CountDownLatch(1);
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
                assertEquals("ok", good.get(0).get("value"));
                assertTrue(connection.isConnected());
            } finally {
                connection.close();
            }
        }
    }

    @Test
    public void untrustworthyTagOrFramingFailsSession() throws Exception {
        try (RouterOsTestServer server = new RouterOsTestServer(new RouterOsTestServer.Handler() {
            @Override
            public void handle(RouterOsTestServer s, RouterOsTestServer.CommandSentence command) throws Exception {
                s.replyWords(RouterOsTestServer.text("!done"),
                        RouterOsTestServer.text(".tag=" + command.tag),
                        RouterOsTestServer.text(".tag=other"));
            }
        })) {
            ApiConnection connection = connect(server);
            final CountDownLatch lifecycle = new CountDownLatch(1);
            connection.addConnectionListener(new ConnectionListener() {
                @Override
                public void connectionLost(ApiConnectionException cause) {
                    lifecycle.countDown();
                }
            });
            connection.execute("/duplicate/tag", new NoopResultListener());
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
