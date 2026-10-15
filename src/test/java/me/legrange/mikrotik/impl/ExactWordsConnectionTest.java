package me.legrange.mikrotik.impl;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import me.legrange.mikrotik.ApiConnection;
import me.legrange.mikrotik.MikrotikApiException;
import me.legrange.mikrotik.ResultListener;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the public exact-words entry point against a native loopback wire peer. */
public class ExactWordsConnectionTest {
    @Test(timeout = 5000)
    public void publicExecuteWordsBypassesParserAndRetainsTaggedReplies() throws Exception {
        List<String> requested = List.of(
                "/interface/wifi/capsman/remote-cap/print",
                "?=base-mac=D0:EA:11:8A:2F:9B",
                "?base-mac",
                "?base-mac=D0:EA:11:8A:2F:9B");
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var worker = Executors.newSingleThreadExecutor();
            try {
                Future<List<String>> seen = worker.submit(() -> {
                    try (Socket socket = server.accept()) {
                        List<String> sentence = Util.readSentence(socket.getInputStream())
                                .stream().map(bytes -> new String(bytes, StandardCharsets.UTF_8)).toList();
                        String tagWord = sentence.stream().filter(w -> w.startsWith(".tag="))
                                .findFirst().orElseThrow();
                        String tag = tagWord.substring(".tag=".length());
                        Util.writeWords(List.of("!re", "=identity=cap-test"), tag, socket.getOutputStream());
                        Util.writeWords(List.of("!done", "=ret=complete"), tag, socket.getOutputStream());
                        socket.getOutputStream().flush();
                        return sentence;
                    }
                });

                ApiConnection client = ApiConnection.connect(
                        SocketFactory.getDefault(), "127.0.0.1", server.getLocalPort(), 1000);
                try {
                    CountDownLatch terminal = new CountDownLatch(1);
                    AtomicReference<Map<String, String>> received = new AtomicReference<>();
                    AtomicReference<Map<String, String>> done = new AtomicReference<>();
                    AtomicReference<MikrotikApiException> failure = new AtomicReference<>();
                    String tag = client.executeWords(requested, new ResultListener() {
                        @Override public void receive(Map<String, String> row) { received.set(row); }
                        @Override public void error(MikrotikApiException error) {
                            failure.set(error);
                            terminal.countDown();
                        }
                        @Override public void completed() { terminal.countDown(); }
                        @Override public void completed(Map<String, String> props) {
                            done.set(props);
                            terminal.countDown();
                        }
                    });
                    assertTrue(terminal.await(2, TimeUnit.SECONDS));
                    assertNull(failure.get());
                    assertEquals("cap-test", received.get().get("identity"));
                    assertEquals("complete", done.get().get("ret"));
                    List<String> transmitted = seen.get(2, TimeUnit.SECONDS);
                    assertEquals(requested, transmitted.subList(0, requested.size()));
                    assertEquals(".tag=" + tag, transmitted.get(transmitted.size() - 1));
                } finally {
                    client.close();
                }
            } finally {
                worker.shutdownNow();
            }
        }
    }
}
