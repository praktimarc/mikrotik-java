package me.legrange.mikrotik.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.legrange.mikrotik.ApiConnectionException;

final class RouterOsTestServer implements AutoCloseable {

    interface Handler {
        void handle(RouterOsTestServer server, CommandSentence command) throws Exception;
    }

    static final class CommandSentence {
        final String command;
        final String tag;
        final Map<String, String> parameters;
        final List<String> queries;
        final List<String> words;

        private CommandSentence(String command, String tag, Map<String, String> parameters,
                List<String> queries, List<String> words) {
            this.command = command;
            this.tag = tag;
            this.parameters = parameters;
            this.queries = queries;
            this.words = words;
        }

        String parameter(String name) {
            return parameters.get(name);
        }
    }

    RouterOsTestServer(Handler handler) throws IOException {
        this.handler = handler;
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                RouterOsTestServer.this.run();
            }
        }, "RouterOsTestServer");
        thread.setDaemon(true);
        thread.start();
    }

    int getPort() {
        return server.getLocalPort();
    }

    boolean awaitClientConnection(long timeoutMs) throws InterruptedException {
        return clientConnected.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    void closeClientConnection() throws IOException {
        Socket current = client;
        if (current != null) {
            current.close();
        }
    }

    synchronized void reply(String type, String tag, String... attributes) throws IOException {
        List<byte[]> words = new ArrayList<byte[]>();
        words.add(text(type));
        for (String attribute : attributes) {
            words.add(text(attribute));
        }
        if (tag != null) {
            words.add(text(".tag=" + tag));
        }
        writeSentence(words);
    }

    synchronized void replyData(String tag, byte[] data) throws IOException {
        List<byte[]> words = new ArrayList<byte[]>();
        words.add(text("!re"));
        byte[] prefix = text("=data=");
        byte[] word = new byte[prefix.length + data.length];
        System.arraycopy(prefix, 0, word, 0, prefix.length);
        System.arraycopy(data, 0, word, prefix.length, data.length);
        words.add(word);
        words.add(text(".tag=" + tag));
        writeSentence(words);
    }

    synchronized void replyRaw(String tag, byte[]... rawAttributes) throws IOException {
        List<byte[]> words = new ArrayList<byte[]>();
        words.add(text("!re"));
        Collections.addAll(words, rawAttributes);
        words.add(text(".tag=" + tag));
        writeSentence(words);
    }

    synchronized void replyWords(byte[]... words) throws IOException {
        writeSentence(Arrays.asList(words));
    }

    synchronized void replyFatal(String... words) throws IOException {
        List<byte[]> reply = new ArrayList<byte[]>();
        reply.add(text("!fatal"));
        for (String word : words) {
            reply.add(text(word));
        }
        writeSentence(reply);
    }

    synchronized void writeRawBytes(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    void assertHealthy() throws Exception {
        if (failure != null) {
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            throw new AssertionError(failure);
        }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        if (client != null) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
        server.close();
        thread.join(1000);
        assertHealthy();
    }

    private void run() {
        try (Socket accepted = server.accept()) {
            client = accepted;
            clientConnected.countDown();
            in = accepted.getInputStream();
            out = accepted.getOutputStream();
            while (!closed) {
                List<byte[]> words;
                try {
                    words = Util.readSentence(in);
                } catch (ApiConnectionException ex) {
                    return;
                }
                if (words.isEmpty()) {
                    continue;
                }
                handler.handle(this, parse(words));
            }
        } catch (Throwable ex) {
            if (!closed) {
                failure = ex;
            }
        }
    }

    private CommandSentence parse(List<byte[]> rawWords) {
        List<String> words = new ArrayList<String>();
        for (byte[] word : rawWords) {
            words.add(new String(word, StandardCharsets.UTF_8));
        }
        String command = words.get(0);
        String tag = null;
        Map<String, String> parameters = new LinkedHashMap<String, String>();
        List<String> queries = new ArrayList<String>();
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            if (word.startsWith(".tag=")) {
                tag = word.substring(5);
            } else if (word.startsWith("?")) {
                queries.add(word);
            } else if (word.startsWith("=")) {
                int separator = word.indexOf('=', 1);
                if (separator >= 0) {
                    parameters.put(word.substring(1, separator), word.substring(separator + 1));
                }
            }
        }
        return new CommandSentence(command, tag, parameters, queries, words);
    }

    private void writeSentence(List<byte[]> words) throws IOException {
        for (byte[] word : words) {
            writeWord(out, word);
        }
        out.write(0);
        out.flush();
    }

    private static void writeWord(OutputStream out, byte[] payload) throws IOException {
        int len = payload.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x4000) {
            int encoded = len | 0x8000;
            out.write(encoded >> 8);
            out.write(encoded);
        } else if (len < 0x20000) {
            int encoded = len | 0xC00000;
            out.write(encoded >> 16);
            out.write(encoded >> 8);
            out.write(encoded);
        } else if (len < 0x10000000) {
            int encoded = len | 0xE0000000;
            out.write(encoded >> 24);
            out.write(encoded >> 16);
            out.write(encoded >> 8);
            out.write(encoded);
        } else {
            out.write(0xF0);
            out.write(len >> 24);
            out.write(len >> 16);
            out.write(len >> 8);
            out.write(len);
        }
        out.write(payload);
    }

    static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private final Handler handler;
    private final ServerSocket server;
    private final Thread thread;
    private final CountDownLatch clientConnected = new CountDownLatch(1);
    private volatile Socket client;
    private volatile InputStream in;
    private volatile OutputStream out;
    private volatile Throwable failure;
    private volatile boolean closed;
}
