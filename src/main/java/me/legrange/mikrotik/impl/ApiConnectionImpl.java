package me.legrange.mikrotik.impl;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;
import me.legrange.mikrotik.ApiConnection;
import me.legrange.mikrotik.ApiConnectionException;
import me.legrange.mikrotik.ConnectionListener;
import me.legrange.mikrotik.MikrotikApiException;
import me.legrange.mikrotik.ResultListener;

/**
 * The Mikrotik API connection implementation. This is the class used to connect
 * to a remote Mikrotik and send commands to it.
 *
 * @author GideonLeGrange
 */
public final class ApiConnectionImpl extends ApiConnection {

    /**
     * Create a new API connection to the give device on the supplied port
     *
     * @param fact The socket factory used to construct the connection socket.
     * @param host The host to which to connect.
     * @param port The TCP port to use.
     * @param timeOut The connection timeout
     * @return The ApiConnection
     * @throws me.legrange.mikrotik.ApiConnectionException Thrown if there is a
     * problem connecting
     */
    public static ApiConnection connect(SocketFactory fact, String host, int port, int timeOut) throws ApiConnectionException {
        ApiConnectionImpl con = new ApiConnectionImpl();
        con.open(host, port, fact, timeOut);
        return con;
    }

    @Override
    public boolean isConnected() {
        return state == ConnectionState.CONNECTED;
    }

    @Override
    public void addConnectionListener(ConnectionListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        ApiConnectionException failure = null;
        synchronized (lifecycleLock) {
            if (containsConnectionListener(listener)) {
                return;
            }
            if (state == ConnectionState.CLOSED) {
                return;
            }
            connectionListeners.add(listener);
            if (state == ConnectionState.FAILED) {
                failure = fatalFailure;
            }
        }
        if (failure != null) {
            notifyConnectionListener(listener, failure);
        }
    }

    @Override
    public void removeConnectionListener(ConnectionListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (lifecycleLock) {
            for (int i = 0; i < connectionListeners.size(); i++) {
                if (connectionListeners.get(i) == listener) {
                    connectionListeners.remove(i);
                    return;
                }
            }
        }
    }

    @Override
    public void login(String username, String password) throws MikrotikApiException {
        if (username.trim().isEmpty()) {
            throw new ApiConnectionException("API username cannot be empty");
        }
        Command cmd = new Command("/login");
        cmd.addParameter("name", username);
        cmd.addParameter("password", password);
        List<Map<String, String>> list = execute(cmd, timeout);
        if (!list.isEmpty()) {
            Map<String, String> res = list.get(0);
            if (res.containsKey("ret")) {
                String hash = res.get("ret");
                String chal = Util.hexStrToStr("00") + new String(password.toCharArray()) + Util.hexStrToStr(hash);
                chal = Util.hashMD5(chal);
                execute("/login name=" + username + " response=00" + chal);
            }
        }
    }

    @Override
    public List<Map<String, String>> execute(String cmd) throws MikrotikApiException {
        return execute(Parser.parse(cmd), timeout);
    }

    @Override
    public String execute(String cmd, ResultListener lis) throws MikrotikApiException {
        return execute(Parser.parse(cmd), lis);
    }

    @Override
    public long downloadFile(String remoteFile, Path localFile) throws MikrotikApiException, IOException {
        if (remoteFile == null) {
            throw new NullPointerException("remoteFile");
        }
        if (localFile == null) {
            throw new NullPointerException("localFile");
        }
        if (remoteFile.trim().isEmpty()) {
            throw new IllegalArgumentException("Remote file must not be blank");
        }
        return FileDownload.download(localFile, new FileDownload.Source() {
            @Override
            public long size() throws MikrotikApiException {
                return getRemoteFileSize(remoteFile);
            }

            @Override
            public byte[] read(long offset, int chunkSize) throws MikrotikApiException {
                return readFileChunk(remoteFile, offset, chunkSize);
            }
        });
    }

    @Override
    public void cancel(String tag) throws MikrotikApiException {
        execute(String.format("/cancel tag=%s", tag));
    }

    @Override
    public void setTimeout(int timeout) throws MikrotikApiException {
        if (timeout > 0) {
            this.timeout = timeout;
        } else {
            throw new MikrotikApiException(String.format("Invalid timeout value '%d'; must be postive", timeout));
        }
    }

    @Override
    public void close() throws ApiConnectionException {
        TerminalSnapshot snapshot;
        ApiConnectionException closeCause = new ApiConnectionException("API connection closed");
        synchronized (lifecycleLock) {
            if (state != ConnectionState.CONNECTED) {
                return;
            }
            state = ConnectionState.CLOSED;
            snapshot = drainCommandsLocked();
            connectionListeners.clear();
        }

        ApiConnectionException shutdownFailure = shutdownTransport();
        notifyCommandFailures(snapshot, closeCause);
        if (shutdownFailure != null) {
            throw shutdownFailure;
        }
    }

    private List<Map<String, String>> execute(Command cmd, int timeout) throws MikrotikApiException {
        SyncListener l = new SyncListener();
        String tag = execute(cmd, l);
        try {
            return l.getResults(timeout);
        } finally {
            removeTextListener(tag, l);
        }
    }

    private String execute(Command cmd, ResultListener lis) throws MikrotikApiException {
        String tag = nextTag();
        cmd.setTag(tag);
        registerTextListener(tag, lis);
        try {
            writeCommand(cmd);
        } catch (MikrotikApiException ex) {
            removeTextListener(tag, lis);
            if (ex instanceof ApiConnectionException) {
                failConnection((ApiConnectionException) ex);
            }
            throw ex;
        }
        return tag;
    }

    private byte[] executeBinaryRead(Command cmd, int timeout) throws MikrotikApiException {
        SyncBinaryListener l = new SyncBinaryListener();
        String tag = nextTag();
        cmd.setTag(tag);
        registerBinaryListener(tag, l);
        try {
            writeCommand(cmd);
        } catch (MikrotikApiException ex) {
            removeBinaryListener(tag, l);
            if (ex instanceof ApiConnectionException) {
                failConnection((ApiConnectionException) ex);
            }
            throw ex;
        }
        try {
            return l.getResult(timeout);
        } finally {
            removeBinaryListener(tag, l);
        }
    }

    private void writeCommand(Command cmd) throws MikrotikApiException {
        synchronized (writeLock) {
            synchronized (lifecycleLock) {
                requireConnectedLocked();
            }
            try {
                Util.write(cmd, out);
            } catch (UnsupportedEncodingException ex) {
                throw new ApiDataException(ex.getMessage(), ex);
            } catch (IOException ex) {
                throw new ApiConnectionException(ex.getMessage(), ex);
            }
        }
    }

    private void registerTextListener(String tag, ResultListener listener) throws ApiConnectionException {
        synchronized (lifecycleLock) {
            requireConnectedLocked();
            listeners.put(tag, listener);
        }
    }

    private void registerBinaryListener(String tag, BinaryResultListener listener) throws ApiConnectionException {
        synchronized (lifecycleLock) {
            requireConnectedLocked();
            binaryListeners.put(tag, listener);
        }
    }

    private void requireConnectedLocked() throws ApiConnectionException {
        if (state == ConnectionState.CONNECTED) {
            return;
        }
        if (state == ConnectionState.FAILED) {
            throw new ApiConnectionException("API connection is no longer usable after a fatal failure", fatalFailure);
        }
        throw new ApiConnectionException("Not/no longer connected to remote Mikrotik");
    }

    private boolean removeTextListener(String tag, ResultListener listener) {
        return listeners.remove(tag, listener);
    }

    private boolean removeBinaryListener(String tag, BinaryResultListener listener) {
        return binaryListeners.remove(tag, listener);
    }

    private long getRemoteFileSize(String remoteFile) throws MikrotikApiException {
        Command cmd = new Command("/file/print");
        cmd.addProperty("size");
        cmd.addQuery("?name=" + remoteFile);
        List<Map<String, String>> results = execute(cmd, timeout);
        if (results.size() != 1) {
            throw new ApiDataException("Expected exactly one RouterOS file named '" + remoteFile
                    + "' but found " + results.size());
        }
        String value = results.get(0).get("size");
        if (value == null) {
            throw new ApiDataException("RouterOS file response contains no size");
        }
        try {
            long size = Long.parseLong(value);
            if (size < 0) {
                throw new ApiDataException("RouterOS file size must not be negative");
            }
            return size;
        } catch (NumberFormatException ex) {
            throw new ApiDataException("Invalid RouterOS file size '" + value + "'", ex);
        }
    }

    private byte[] readFileChunk(String remoteFile, long offset, int chunkSize) throws MikrotikApiException {
        if (offset < 0) {
            throw new ApiDataException("File offset must not be negative");
        }
        if (chunkSize < 1 || chunkSize > FileDownload.CHUNK_SIZE) {
            throw new ApiDataException("File read chunk size must be between 1 and " + FileDownload.CHUNK_SIZE);
        }
        Command cmd = new Command("/file/read");
        cmd.addParameter("file", remoteFile);
        cmd.addParameter("offset", Long.toString(offset));
        cmd.addParameter("chunk-size", Integer.toString(chunkSize));
        return executeBinaryRead(cmd, timeout);
    }

    private ApiConnectionImpl() {
        this.listeners = new ConcurrentHashMap<>();
        this.binaryListeners = new ConcurrentHashMap<>();
    }

    /**
     * Start the API. Connects to the Mikrotik
     */
    private void open(String host, int port, SocketFactory fact, int conTimeout) throws ApiConnectionException {
        try {
            InetAddress ia = InetAddress.getByName(host.trim());
            sock = fact.createSocket();
            sock.connect(new InetSocketAddress(ia, port), conTimeout);
            in = new DataInputStream(sock.getInputStream());
            out = new DataOutputStream(sock.getOutputStream());
            synchronized (lifecycleLock) {
                state = ConnectionState.CONNECTED;
                fatalFailure = null;
            }
            reader = new Reader();
            reader.setDaemon(true);
            reader.start();
            processor = new Processor();
            processor.setDaemon(true);
            processor.start();
        } catch (UnknownHostException ex) {
            synchronized (lifecycleLock) {
                state = ConnectionState.CLOSED;
            }
            throw new ApiConnectionException(String.format("Unknown host '%s'", host), ex);
        } catch (IOException ex) {
            synchronized (lifecycleLock) {
                state = ConnectionState.CLOSED;
            }
            shutdownTransport();
            throw new ApiConnectionException(String.format("Error connecting to %s:%d : %s", host, port, ex.getMessage()), ex);
        }
    }

    private synchronized String nextTag() {
        return Integer.toHexString(_tag.incrementAndGet());
    }

    private void failConnection(ApiConnectionException failure) {
        TerminalSnapshot snapshot;
        List<ConnectionListener> lifecycleListeners;
        synchronized (lifecycleLock) {
            if (state != ConnectionState.CONNECTED) {
                return;
            }
            state = ConnectionState.FAILED;
            fatalFailure = failure;
            snapshot = drainCommandsLocked();
            lifecycleListeners = new ArrayList<>(connectionListeners);
        }

        ApiConnectionException shutdownFailure = shutdownTransport();
        if (shutdownFailure != null) {
            failure.addSuppressed(shutdownFailure);
        }
        notifyCommandFailures(snapshot, failure);
        for (ConnectionListener listener : lifecycleListeners) {
            notifyConnectionListener(listener, failure);
        }
    }

    private TerminalSnapshot drainCommandsLocked() {
        List<ResultListener> text = new ArrayList<>(listeners.values());
        List<BinaryResultListener> binary = new ArrayList<>(binaryListeners.values());
        listeners.clear();
        binaryListeners.clear();
        return new TerminalSnapshot(text, binary);
    }

    private void notifyCommandFailures(TerminalSnapshot snapshot, ApiConnectionException failure) {
        for (ResultListener listener : snapshot.textListeners) {
            try {
                listener.error(failure);
            } catch (RuntimeException ignored) {
            }
        }
        for (BinaryResultListener listener : snapshot.binaryListeners) {
            try {
                listener.error(failure);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void notifyConnectionListener(ConnectionListener listener, ApiConnectionException failure) {
        try {
            listener.connectionLost(failure);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean containsConnectionListener(ConnectionListener candidate) {
        for (ConnectionListener listener : connectionListeners) {
            if (listener == candidate) {
                return true;
            }
        }
        return false;
    }

    private ApiConnectionException shutdownTransport() {
        if (processor != null) {
            processor.interrupt();
        }
        if (reader != null) {
            reader.interrupt();
        }

        IOException first = null;
        first = closeSocket(first);
        first = closeInput(first);
        first = closeOutput(first);
        if (first == null) {
            return null;
        }
        return new ApiConnectionException(String.format("Error closing socket: %s", first.getMessage()), first);
    }

    private IOException closeSocket(IOException first) {
        if (sock != null) {
            try {
                sock.close();
            } catch (IOException ex) {
                if (first == null) {
                    first = ex;
                }
            }
        }
        return first;
    }

    private IOException closeInput(IOException first) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ex) {
                if (first == null) {
                    first = ex;
                }
            }
        }
        return first;
    }

    private IOException closeOutput(IOException first) {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ex) {
                if (first == null) {
                    first = ex;
                }
            }
        }
        return first;
    }

    private enum ConnectionState {
        CONNECTED,
        CLOSED,
        FAILED
    }

    private static final class TerminalSnapshot {
        private final List<ResultListener> textListeners;
        private final List<BinaryResultListener> binaryListeners;

        private TerminalSnapshot(List<ResultListener> textListeners, List<BinaryResultListener> binaryListeners) {
            this.textListeners = textListeners;
            this.binaryListeners = binaryListeners;
        }
    }

    private Socket sock = null;
    private DataOutputStream out = null;
    private DataInputStream in = null;
    private volatile ConnectionState state = ConnectionState.CLOSED;
    private ApiConnectionException fatalFailure;
    private Reader reader;
    private Processor processor;
    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();
    private final Map<String, ResultListener> listeners;
    private final Map<String, BinaryResultListener> binaryListeners;
    private final List<ConnectionListener> connectionListeners = new ArrayList<>();
    private final AtomicInteger _tag = new AtomicInteger(0);
    private int timeout = ApiConnection.DEFAULT_COMMAND_TIMEOUT;

    /**
     * Thread to read complete raw RouterOS sentences from the socket.
     */
    private class Reader extends Thread {

        private Reader() {
            super("Mikrotik API Reader");
        }

        private RawSentence take() throws InterruptedException {
            return queue.take();
        }

        @Override
        public void run() {
            while (isConnected()) {
                try {
                    queue.put(new RawSentence(Util.readSentence(in)));
                } catch (ApiDataException ex) {
                    failConnection(new ApiConnectionException(
                            "RouterOS API protocol error: " + ex.getMessage(), ex));
                    return;
                } catch (ApiConnectionException ex) {
                    if (isConnected()) {
                        failConnection(ex);
                    }
                    return;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private final LinkedBlockingQueue<RawSentence> queue = new LinkedBlockingQueue<>();
    }

    /**
     * Thread to turn raw sentences into existing response objects and listeners.
     */
    private class Processor extends Thread {

        private Processor() {
            super("Mikrotik API Result Processor");
        }

        @Override
        public void run() {
            while (isConnected()) {
                try {
                    RawSentence sentence = reader.take();
                    if (!isConnected()) {
                        return;
                    }
                    process(sentence);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void process(RawSentence sentence) {
            final String type;
            try {
                type = sentence.getType();
            } catch (ApiDataException ex) {
                failProtocol(ex);
                return;
            }

            if ("!fatal".equals(type)) {
                try {
                    failConnection(new ApiConnectionException(
                            "RouterOS API fatal error: " + sentence.getFatalDiagnostic()));
                } catch (ApiDataException ex) {
                    failProtocol(ex);
                }
                return;
            }

            if (!isKnownReplyType(type)) {
                failProtocol(new ApiDataException("unexpected response type"));
                return;
            }

            final String tag;
            try {
                tag = sentence.getTag();
            } catch (ApiDataException ex) {
                failProtocol(ex);
                return;
            }

            if ("!empty".equals(type)) {
                return;
            }

            BinaryResultListener binary = tag == null ? null : binaryListeners.get(tag);
            if (binary != null) {
                dispatchBinary(sentence, tag, binary);
                return;
            }

            try {
                dispatch(sentence.toTextResponse());
            } catch (MikrotikApiException ex) {
                failTaggedCommand(tag, ex);
            }
        }

        private boolean isKnownReplyType(String type) {
            switch (type) {
                case "!re":
                case "!done":
                case "!trap":
                case "!halt":
                case "!empty":
                    return true;
                default:
                    return false;
            }
        }

        private void failProtocol(ApiDataException failure) {
            failConnection(new ApiConnectionException(
                    "RouterOS API protocol error: " + failure.getMessage(), failure));
        }

        private void failTaggedCommand(String tag, MikrotikApiException failure) {
            if (tag == null) {
                if (failure instanceof ApiDataException) {
                    failProtocol((ApiDataException) failure);
                } else {
                    failConnection(new ApiConnectionException(
                            "RouterOS API response could not be processed", failure));
                }
                return;
            }

            ResultListener text = listeners.get(tag);
            if (text != null && removeTextListener(tag, text)) {
                text.error(failure);
                return;
            }

            BinaryResultListener binary = binaryListeners.get(tag);
            if (binary != null && removeBinaryListener(tag, binary)) {
                binary.error(failure);
            }
        }

        private void dispatchBinary(RawSentence sentence, String tag, BinaryResultListener l) {
            try {
                switch (sentence.getType()) {
                    case "!re":
                        l.receive(sentence.requireSingleRawAttribute("data"));
                        break;
                    case "!done":
                        if (removeBinaryListener(tag, l)) {
                            l.completed();
                        }
                        break;
                    case "!trap":
                    case "!halt":
                        ApiCommandException commandError = new ApiCommandException((Error) sentence.toTextResponse());
                        if (removeBinaryListener(tag, l)) {
                            l.error(commandError);
                        }
                        break;
                    default:
                        ApiDataException dataError = new ApiDataException(
                                "Unexpected binary response type '" + sentence.getType() + "'");
                        if (removeBinaryListener(tag, l)) {
                            l.error(dataError);
                        }
                        break;
                }
            } catch (MikrotikApiException ex) {
                if (removeBinaryListener(tag, l)) {
                    l.error(ex);
                }
            }
        }

        private void dispatch(Response res) {
            if (res.getTag() != null) {
                ResultListener l = listeners.get(res.getTag());
                if (l != null) {
                    if (res instanceof Result) {
                        l.receive((Result) res);
                    } else if (res instanceof Done) {
                        if (removeTextListener(res.getTag(), l)) {
                            if (l instanceof SyncListener) {
                                ((SyncListener) l).completed((Done) res);
                            } else {
                                l.completed();
                            }
                        }
                    } else if (res instanceof Error) {
                        ApiCommandException commandError = new ApiCommandException((Error) res);
                        if (removeTextListener(res.getTag(), l)) {
                            l.error(commandError);
                        }
                    }
                }
            } else {
                nextTag();
            }
        }
    }

    private static class SyncBinaryListener implements BinaryResultListener {

        @Override
        public synchronized void receive(byte[] data) {
            if (received) {
                err = new ApiDataException("Binary command returned multiple data results");
                complete = true;
                notifyAll();
                return;
            }
            this.data = Arrays.copyOf(data, data.length);
            received = true;
        }

        @Override
        public synchronized void error(MikrotikApiException ex) {
            err = ex;
            complete = true;
            notifyAll();
        }

        @Override
        public synchronized void completed() {
            if (!received && err == null) {
                err = new ApiDataException("Binary command completed without data");
            }
            complete = true;
            notifyAll();
        }

        private byte[] getResult(int timeout) throws MikrotikApiException {
            try {
                synchronized (this) {
                    int waitTime = timeout;
                    while (!complete && waitTime > 0) {
                        long start = System.currentTimeMillis();
                        wait(waitTime);
                        waitTime -= (int) (System.currentTimeMillis() - start);
                    }
                    if (!complete) {
                        throw new ApiConnectionException(String.format("Command timed out after %d ms", timeout));
                    }
                }
            } catch (InterruptedException ex) {
                throw new ApiConnectionException(ex.getMessage(), ex);
            }
            if (err != null) {
                throw err;
            }
            return data;
        }

        private byte[] data;
        private MikrotikApiException err;
        private boolean received;
        private boolean complete;
    }

    private static class SyncListener implements ResultListener {

        @Override
        public synchronized void error(MikrotikApiException ex) {
            this.err = ex;
            this.complete = true;
            notifyAll();
        }

        @Override
        public synchronized void completed() {
            complete = true;
            notifyAll();
        }

        synchronized void completed(Done done) {
            if (done.getHash() != null) {
                Result res = new Result();
                res.put("ret", done.getHash());
                results.add(res);
            }
            complete = true;
            notifyAll();
        }

        @Override
        public void receive(Map<String, String> result) {
            results.add(result);
        }

        private List<Map<String, String>> getResults(int timeout) throws MikrotikApiException {
            try {
                synchronized (this) {
                    int waitTime = timeout;
                    while (!complete && (waitTime > 0)) {
                        long start = System.currentTimeMillis();
                        wait(waitTime);
                        waitTime = waitTime - (int) (System.currentTimeMillis() - start);
                        if ((waitTime <= 0) && !complete) {
                            err = new ApiConnectionException(String.format("Command timed out after %d ms", timeout));
                        }
                    }
                }
            } catch (InterruptedException ex) {
                throw new ApiConnectionException(ex.getMessage(), ex);
            }
            if (err != null) {
                throw err;
            }
            return results;
        }

        private final List<Map<String, String>> results = new LinkedList<>();
        private MikrotikApiException err;
        private boolean complete = false;
    }
}
