package me.legrange.mikrotik.impl;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;
import me.legrange.mikrotik.ApiConnection;
import me.legrange.mikrotik.ApiConnectionException;
import me.legrange.mikrotik.ApiDataException;
import me.legrange.mikrotik.ConnectionListener;
import me.legrange.mikrotik.MikrotikApiException;
import me.legrange.mikrotik.ResultListener;

/**
 * Implementation of the API connection. Internal to the library.
 */
public final class ApiConnectionImpl extends ApiConnection {

    private enum ConnectionState {
        CONNECTED,
        CLOSED,
        FAILED
    }

    private static final int DEFAULT_COMMAND_TIMEOUT = 60000;

    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();
    private final Map<String, ResultListener> listeners = new ConcurrentHashMap<>();
    private final Map<String, BinaryResultListener> binaryListeners = new ConcurrentHashMap<>();
    private final Set<ConnectionListener> connectionListeners = new HashSet<>();
    private final AtomicInteger tag = new AtomicInteger();
    private final BlockingQueue<RawSentence> queue = new ArrayBlockingQueue<>(40);

    private volatile ConnectionState state = ConnectionState.CLOSED;
    private volatile ApiConnectionException fatalCause;
    private volatile int timeout = DEFAULT_COMMAND_TIMEOUT;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread readerThread;
    private Thread processorThread;

    ApiConnectionImpl() {
    }

    @Override
    protected void connect(SocketFactory factory, String host, int port, int timeout) throws ApiConnectionException {
        synchronized (lifecycleLock) {
            if (state == ConnectionState.CONNECTED) {
                throw new ApiConnectionException("Already connected");
            }
            try {
                socket = factory.createSocket(host, port);
                socket.setSoTimeout(timeout);
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());
                state = ConnectionState.CONNECTED;
                fatalCause = null;
                startThreads();
            } catch (IOException ex) {
                cleanupTransport();
                throw new ApiConnectionException("Error connecting to " + host + ":" + port, ex);
            }
        }
    }

    @Override
    public boolean isConnected() {
        return state == ConnectionState.CONNECTED;
    }

    @Override
    public void login(String username, String password) throws MikrotikApiException {
        List<Map<String, String>> result = execute("/login name=" + username + " password=" + password);
        if (!result.isEmpty()) {
            String ret = result.get(0).get("ret");
            if (ret != null) {
                String chal = Util.hexStrToStr(Util.hashMD5((char) 0 + password + Util.hexStrToStr(ret)));
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
    public long downloadFile(final String remoteFile, Path localFile) throws MikrotikApiException, IOException {
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
        synchronized (lifecycleLock) {
            if (state == ConnectionState.CLOSED) {
                return;
            }
            if (state == ConnectionState.FAILED) {
                cleanupTransport();
                return;
            }
            state = ConnectionState.CLOSED;
            snapshot = drainActiveCommands(new ApiConnectionException("Connection closed"), false);
        }
        cleanupTransport();
        signalTerminal(snapshot);
    }

    @Override
    public void addConnectionListener(ConnectionListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        ApiConnectionException immediate = null;
        synchronized (lifecycleLock) {
            if (state == ConnectionState.FAILED) {
                immediate = fatalCause;
            } else if (state != ConnectionState.CLOSED) {
                connectionListeners.add(listener);
            }
        }
        if (immediate != null) {
            notifyConnectionListener(listener, immediate);
        }
    }

    @Override
    public void removeConnectionListener(ConnectionListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (lifecycleLock) {
            connectionListeners.remove(listener);
        }
    }

    private String execute(Command command, ResultListener listener) throws MikrotikApiException {
        String commandTag = nextTag();
        command.setTag(commandTag);
        registerText(commandTag, listener);
        try {
            writeCommand(command);
            return commandTag;
        } catch (MikrotikApiException ex) {
            listeners.remove(commandTag, listener);
            throw ex;
        }
    }

    private List<Map<String, String>> execute(Command command, int timeout) throws MikrotikApiException {
        SyncListener listener = new SyncListener();
        String commandTag = null;
        try {
            commandTag = execute(command, listener);
            return listener.getResults(timeout);
        } finally {
            if (commandTag != null) {
                listeners.remove(commandTag, listener);
            }
        }
    }

    private byte[] executeBinaryRead(Command command, int timeout) throws MikrotikApiException {
        String commandTag = nextTag();
        command.setTag(commandTag);
        SyncBinaryListener listener = new SyncBinaryListener();
        registerBinary(commandTag, listener);
        try {
            writeCommand(command);
            return listener.getData(timeout);
        } finally {
            binaryListeners.remove(commandTag, listener);
        }
    }

    private long getRemoteFileSize(String remoteFile) throws MikrotikApiException {
        Command command = new Command("/file/print");
        command.addQuery("?name=" + remoteFile);
        command.addParameter("=.proplist=size");
        List<Map<String, String>> rows = execute(command, timeout);
        if (rows.size() != 1) {
            throw new ApiDataException("Expected exactly one RouterOS file named '" + remoteFile + "'");
        }
        String sizeValue = rows.get(0).get("size");
        if (sizeValue == null) {
            throw new ApiDataException("RouterOS file response did not contain size for '" + remoteFile + "'");
        }
        try {
            long size = Long.parseLong(sizeValue);
            if (size < 0) {
                throw new ApiDataException("Invalid negative RouterOS file size: " + sizeValue);
            }
            return size;
        } catch (NumberFormatException ex) {
            throw new ApiDataException("Invalid RouterOS file size: " + sizeValue, ex);
        }
    }

    private byte[] readFileChunk(String remoteFile, long offset, int chunkSize) throws MikrotikApiException {
        Command command = new Command("/file/read");
        command.addParameter("=file=" + remoteFile);
        command.addParameter("=offset=" + offset);
        command.addParameter("=chunk-size=" + chunkSize);
        return executeBinaryRead(command, timeout);
    }

    private void registerText(String commandTag, ResultListener listener) throws ApiConnectionException {
        synchronized (lifecycleLock) {
            ensureConnected();
            listeners.put(commandTag, listener);
        }
    }

    private void registerBinary(String commandTag, BinaryResultListener listener) throws ApiConnectionException {
        synchronized (lifecycleLock) {
            ensureConnected();
            binaryListeners.put(commandTag, listener);
        }
    }

    private void writeCommand(Command command) throws MikrotikApiException {
        TerminalSnapshot failureSnapshot = null;
        ApiConnectionException writeFailure = null;
        synchronized (writeLock) {
            ensureConnected();
            try {
                Util.write(command, out);
            } catch (ApiConnectionException ex) {
                rollbackRegistration(command.getTag());
                writeFailure = ex;
                synchronized (lifecycleLock) {
                    failureSnapshot = transitionToFailedLocked(ex);
                }
            } catch (MikrotikApiException ex) {
                rollbackRegistration(command.getTag());
                throw ex;
            }
        }
        if (writeFailure != null) {
            finishFailedTransition(failureSnapshot);
            throw writeFailure;
        }
    }

    private void rollbackRegistration(String commandTag) {
        if (commandTag == null) {
            return;
        }
        listeners.remove(commandTag);
        binaryListeners.remove(commandTag);
    }

    private void ensureConnected() throws ApiConnectionException {
        if (state == ConnectionState.CONNECTED) {
            return;
        }
        ApiConnectionException failure = fatalCause;
        if (failure != null) {
            throw new ApiConnectionException("Connection has failed", failure);
        }
        throw new ApiConnectionException("Not connected");
    }

    private String nextTag() {
        return Integer.toString(tag.incrementAndGet());
    }

    private void startThreads() {
        readerThread = new Thread(new Reader(), "Mikrotik API reader");
        readerThread.setDaemon(true);
        processorThread = new Thread(new Processor(), "Mikrotik API processor");
        processorThread.setDaemon(true);
        readerThread.start();
        processorThread.start();
    }

    private void failConnection(ApiConnectionException cause) {
        TerminalSnapshot snapshot;
        synchronized (lifecycleLock) {
            snapshot = transitionToFailedLocked(cause);
        }
        finishFailedTransition(snapshot);
    }

    private TerminalSnapshot transitionToFailedLocked(ApiConnectionException cause) {
        if (state != ConnectionState.CONNECTED) {
            return null;
        }
        state = ConnectionState.FAILED;
        fatalCause = cause;
        return drainActiveCommands(cause, true);
    }

    private void finishFailedTransition(TerminalSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        cleanupTransport();
        signalTerminal(snapshot);
    }

    private TerminalSnapshot drainActiveCommands(ApiConnectionException cause, boolean notifyLifecycle) {
        Map<String, ResultListener> text = new LinkedHashMap<>(listeners);
        listeners.clear();
        Map<String, BinaryResultListener> binary = new LinkedHashMap<>(binaryListeners);
        binaryListeners.clear();
        List<ConnectionListener> lifecycle = notifyLifecycle
                ? new ArrayList<>(connectionListeners) : Collections.<ConnectionListener>emptyList();
        connectionListeners.clear();
        return new TerminalSnapshot(cause, text, binary, lifecycle);
    }

    private void signalTerminal(TerminalSnapshot snapshot) {
        for (ResultListener listener : snapshot.text.values()) {
            try {
                listener.error(snapshot.cause);
            } catch (RuntimeException ignored) {
            }
        }
        for (BinaryResultListener listener : snapshot.binary.values()) {
            try {
                listener.error(snapshot.cause);
            } catch (RuntimeException ignored) {
            }
        }
        for (ConnectionListener listener : snapshot.lifecycle) {
            notifyConnectionListener(listener, snapshot.cause);
        }
    }

    private void notifyConnectionListener(ConnectionListener listener, ApiConnectionException cause) {
        try {
            listener.connectionLost(cause);
        } catch (RuntimeException ignored) {
        }
    }

    private void cleanupTransport() {
        Thread reader = readerThread;
        Thread processor = processorThread;
        if (reader != null && reader != Thread.currentThread()) {
            reader.interrupt();
        }
        if (processor != null && processor != Thread.currentThread()) {
            processor.interrupt();
        }
        closeQuietly(in);
        closeQuietly(out);
        Socket current = socket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void dispatch(Response response) throws MikrotikApiException {
        String responseTag = response.getTag();
        if (responseTag == null) {
            throw new ApiDataException("RouterOS response did not contain a command tag");
        }
        ResultListener textListener = listeners.get(responseTag);
        BinaryResultListener binaryListener = binaryListeners.get(responseTag);
        if (textListener != null && binaryListener != null) {
            throw new ApiDataException("RouterOS response tag is registered for multiple command types: " + responseTag);
        }
        if (textListener != null) {
            dispatchText(responseTag, textListener, response);
        } else if (binaryListener != null) {
            dispatchBinary(responseTag, binaryListener, response);
        }
    }

    private void dispatchText(String responseTag, ResultListener listener, Response response) throws MikrotikApiException {
        if (response instanceof Result) {
            listener.receive(((Result) response).getResult());
        } else if (response instanceof Done) {
            if (listeners.remove(responseTag, listener)) {
                listener.completed();
            }
        } else if (response instanceof Error) {
            if (listeners.remove(responseTag, listener)) {
                listener.error(((Error) response).toException());
            }
        }
    }

    private void dispatchBinary(String responseTag, BinaryResultListener listener, Response response) throws MikrotikApiException {
        try {
            if (response instanceof Result) {
                listener.receive(((Result) response).getBinaryValue("data"));
            } else if (response instanceof Done) {
                if (binaryListeners.remove(responseTag, listener)) {
                    listener.completed();
                }
            } else if (response instanceof Error) {
                if (binaryListeners.remove(responseTag, listener)) {
                    listener.error(((Error) response).toException());
                }
            }
        } catch (MikrotikApiException ex) {
            if (binaryListeners.remove(responseTag, listener)) {
                listener.error(ex);
            }
        }
    }

    private void failTaggedCommand(String responseTag, MikrotikApiException cause) {
        ResultListener text = listeners.remove(responseTag);
        if (text != null) {
            try {
                text.error(cause);
            } catch (RuntimeException ignored) {
            }
        }
        BinaryResultListener binary = binaryListeners.remove(responseTag);
        if (binary != null) {
            try {
                binary.error(cause);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private final class Reader implements Runnable {
        @Override
        public void run() {
            while (state == ConnectionState.CONNECTED) {
                try {
                    RawSentence sentence = RawSentence.read(in);
                    queue.put(sentence);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ApiConnectionException ex) {
                    if (state == ConnectionState.CONNECTED) {
                        failConnection(ex);
                    }
                    return;
                } catch (RuntimeException ex) {
                    if (state == ConnectionState.CONNECTED) {
                        failConnection(new ApiConnectionException("Unexpected RouterOS API reader failure", ex));
                    }
                    return;
                }
            }
        }
    }

    private final class Processor implements Runnable {
        @Override
        public void run() {
            while (state == ConnectionState.CONNECTED || !queue.isEmpty()) {
                try {
                    RawSentence sentence = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (sentence == null) {
                        continue;
                    }
                    processSentence(sentence);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void processSentence(RawSentence sentence) {
            String type;
            try {
                type = sentence.getType();
            } catch (ApiDataException ex) {
                failConnection(new ApiConnectionException("Invalid RouterOS API response", ex));
                return;
            }

            if ("!empty".equals(type)) {
                return;
            }
            if ("!fatal".equals(type)) {
                failConnection(new ApiConnectionException(sentence.getFatalMessage()));
                return;
            }
            if (!RawSentence.isKnownResponseType(type)) {
                failConnection(new ApiConnectionException("Unknown RouterOS API response type: " + type,
                        new ApiDataException("Unknown RouterOS API response type: " + type)));
                return;
            }

            String responseTag;
            try {
                responseTag = sentence.getTag();
            } catch (ApiDataException ex) {
                failConnection(new ApiConnectionException("Untrustworthy RouterOS API response routing", ex));
                return;
            }
            if (responseTag == null) {
                failConnection(new ApiConnectionException("RouterOS API response is missing a tag",
                        new ApiDataException("RouterOS API response did not contain a command tag")));
                return;
            }

            try {
                dispatch(sentence.toTextResponse());
            } catch (ApiDataException ex) {
                failTaggedCommand(responseTag, ex);
            } catch (MikrotikApiException ex) {
                failConnection(new ApiConnectionException("Failed to process RouterOS API response", ex));
            } catch (RuntimeException ex) {
                failConnection(new ApiConnectionException("Unexpected RouterOS API processor failure", ex));
            }
        }
    }

    private static final class TerminalSnapshot {
        final ApiConnectionException cause;
        final Map<String, ResultListener> text;
        final Map<String, BinaryResultListener> binary;
        final List<ConnectionListener> lifecycle;

        TerminalSnapshot(ApiConnectionException cause, Map<String, ResultListener> text,
                Map<String, BinaryResultListener> binary, List<ConnectionListener> lifecycle) {
            this.cause = cause;
            this.text = text;
            this.binary = binary;
            this.lifecycle = lifecycle;
        }
    }

    private static final class SyncListener implements ResultListener {
        private final List<Map<String, String>> results = new ArrayList<>();
        private MikrotikApiException error;
        private boolean complete;

        @Override
        public synchronized void receive(Map<String, String> result) {
            results.add(result);
        }

        @Override
        public synchronized void error(MikrotikApiException ex) {
            if (!complete) {
                error = ex;
                complete = true;
                notifyAll();
            }
        }

        @Override
        public synchronized void completed() {
            if (!complete) {
                complete = true;
                notifyAll();
            }
        }

        synchronized List<Map<String, String>> getResults(int timeout) throws MikrotikApiException {
            long deadline = System.currentTimeMillis() + timeout;
            while (!complete) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new ApiConnectionException("Command timed out");
                }
                try {
                    wait(remaining);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new ApiConnectionException("Interrupted while waiting for command response", ex);
                }
            }
            if (error != null) {
                throw error;
            }
            return results;
        }
    }

    private static final class SyncBinaryListener implements BinaryResultListener {
        private byte[] data;
        private MikrotikApiException error;
        private boolean complete;

        @Override
        public synchronized void receive(byte[] value) throws MikrotikApiException {
            if (data != null) {
                throw new ApiDataException("RouterOS binary response contained more than one data value");
            }
            data = value;
        }

        @Override
        public synchronized void error(MikrotikApiException ex) {
            if (!complete) {
                error = ex;
                complete = true;
                notifyAll();
            }
        }

        @Override
        public synchronized void completed() {
            if (!complete) {
                complete = true;
                notifyAll();
            }
        }

        synchronized byte[] getData(int timeout) throws MikrotikApiException {
            long deadline = System.currentTimeMillis() + timeout;
            while (!complete) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new ApiConnectionException("Command timed out");
                }
                try {
                    wait(remaining);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new ApiConnectionException("Interrupted while waiting for binary response", ex);
                }
            }
            if (error != null) {
                throw error;
            }
            if (data == null) {
                throw new ApiDataException("RouterOS binary response did not contain data");
            }
            return data;
        }
    }
}
