package me.legrange.mikrotik.impl;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.legrange.mikrotik.ApiConnectionException;
import me.legrange.mikrotik.ApiCommandException;
import me.legrange.mikrotik.MikrotikApiException;
import me.legrange.mikrotik.ResultListener;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ApiConnectionLifecycleTest {

    @Test
    public void doneRemovesTextRegistrationBeforeCompletedCallback() throws Exception {
        ApiConnectionImpl connection = newConnection();
        final Map<String, ResultListener> listeners = textListeners(connection);
        final String tag = "done-before-callback";
        final AtomicBoolean removedDuringCallback = new AtomicBoolean(false);
        ResultListener listener = new NoopResultListener() {
            @Override
            public void completed() {
                removedDuringCallback.set(!listeners.containsKey(tag));
            }
        };
        listeners.put(tag, listener);

        dispatchText(connection, new Done(tag));

        assertTrue(removedDuringCallback.get());
        assertFalse(listeners.containsKey(tag));
    }

    @Test
    public void trapAndLegacyHaltRemoveTextRegistrationBeforeErrorCallback() throws Exception {
        assertTextErrorRemovesBeforeCallback("!trap");
        assertTextErrorRemovesBeforeCallback("!halt");
    }

    @Test
    public void throwingTerminalCallbackStillLeavesNoTextRegistration() throws Exception {
        ApiConnectionImpl connection = newConnection();
        Map<String, ResultListener> listeners = textListeners(connection);
        String tag = "throwing-callback";
        listeners.put(tag, new NoopResultListener() {
            @Override
            public void completed() {
                throw new IllegalStateException("listener failed");
            }
        });

        try {
            dispatchText(connection, new Done(tag));
            fail("Expected listener callback failure");
        } catch (IllegalStateException expected) {
            assertEquals("listener failed", expected.getMessage());
        }

        assertFalse(listeners.containsKey(tag));
    }

    @Test
    public void doneAfterTrapDoesNotInvokeCompleted() throws Exception {
        ApiConnectionImpl connection = newConnection();
        Map<String, ResultListener> listeners = textListeners(connection);
        String tag = "trap-then-done";
        final AtomicInteger errors = new AtomicInteger();
        final AtomicInteger completions = new AtomicInteger();
        listeners.put(tag, new NoopResultListener() {
            @Override
            public void error(MikrotikApiException ex) {
                errors.incrementAndGet();
            }

            @Override
            public void completed() {
                completions.incrementAndGet();
            }
        });

        dispatchText(connection, errorResponse("!trap", tag));
        dispatchText(connection, new Done(tag));

        assertEquals(1, errors.get());
        assertEquals(0, completions.get());
        assertFalse(listeners.containsKey(tag));
    }

    @Test
    public void synchronousTimeoutRemovesItsTextRegistration() throws Exception {
        ApiConnectionImpl connection = newConnectedConnection();
        setOutput(connection, new DataOutputStream(new ByteArrayOutputStream()));

        Throwable thrown = invokeSynchronousExecute(connection, new Command("/test/timeout"), 25);

        assertTrue(thrown instanceof ApiConnectionException);
        assertTrue(thrown.getMessage().contains("timed out"));
        assertTrue(textListeners(connection).isEmpty());
    }

    @Test
    public void asyncWriteFailureRollsBackTextRegistration() throws Exception {
        ApiConnectionImpl connection = newConnectedConnection();
        setOutput(connection, new DataOutputStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("deterministic write failure");
            }
        }));

        Throwable thrown = invokeAsyncExecute(connection, new Command("/test/write-failure"), new NoopResultListener());

        assertTrue(thrown instanceof ApiConnectionException);
        assertTrue(textListeners(connection).isEmpty());
    }

    @Test
    public void doneRemovesBinaryRegistrationBeforeCompletedCallback() throws Exception {
        ApiConnectionImpl connection = newConnection();
        final Map<String, BinaryResultListener> listeners = binaryListeners(connection);
        final String tag = "binary-done";
        final AtomicBoolean removedDuringCallback = new AtomicBoolean(false);
        BinaryResultListener listener = new BinaryResultListener() {
            @Override
            public void receive(byte[] data) {
            }

            @Override
            public void error(MikrotikApiException ex) {
            }

            @Override
            public void completed() {
                removedDuringCallback.set(!listeners.containsKey(tag));
            }
        };
        listeners.put(tag, listener);

        dispatchBinary(connection, sentence("!done", ".tag=" + tag), tag, listener);

        assertTrue(removedDuringCallback.get());
        assertFalse(listeners.containsKey(tag));
    }

    private static void assertTextErrorRemovesBeforeCallback(String type) throws Exception {
        ApiConnectionImpl connection = newConnection();
        final Map<String, ResultListener> listeners = textListeners(connection);
        final String tag = type.substring(1) + "-before-callback";
        final AtomicBoolean removedDuringCallback = new AtomicBoolean(false);
        ResultListener listener = new NoopResultListener() {
            @Override
            public void error(MikrotikApiException ex) {
                assertTrue(ex instanceof ApiCommandException);
                removedDuringCallback.set(!listeners.containsKey(tag));
            }
        };
        listeners.put(tag, listener);

        dispatchText(connection, errorResponse(type, tag));

        assertTrue(type + " registration should be absent during error callback", removedDuringCallback.get());
        assertFalse(listeners.containsKey(tag));
    }

    private static Error errorResponse(String type, String tag) throws MikrotikApiException {
        return (Error) sentence(type, "=message=failed", "=category=2", ".tag=" + tag).toTextResponse();
    }

    private static RawSentence sentence(String... words) {
        byte[][] encoded = new byte[words.length][];
        for (int i = 0; i < words.length; i++) {
            encoded[i] = words[i].getBytes(StandardCharsets.UTF_8);
        }
        return new RawSentence(Arrays.asList(encoded));
    }

    private static void dispatchText(ApiConnectionImpl connection, Response response) throws Exception {
        Object processor = newProcessor(connection);
        Method dispatch = processor.getClass().getDeclaredMethod("dispatch", Response.class);
        dispatch.setAccessible(true);
        try {
            dispatch.invoke(processor, response);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ex.getCause();
            }
            throw ex;
        }
    }

    private static void dispatchBinary(ApiConnectionImpl connection, RawSentence sentence,
            String tag, BinaryResultListener listener) throws Exception {
        Object processor = newProcessor(connection);
        Method dispatch = processor.getClass().getDeclaredMethod(
                "dispatchBinary", RawSentence.class, String.class, BinaryResultListener.class);
        dispatch.setAccessible(true);
        dispatch.invoke(processor, sentence, tag, listener);
    }

    private static Object newProcessor(ApiConnectionImpl connection) throws Exception {
        Class<?> type = Class.forName("me.legrange.mikrotik.impl.ApiConnectionImpl$Processor");
        Constructor<?> constructor = type.getDeclaredConstructor(ApiConnectionImpl.class);
        constructor.setAccessible(true);
        return constructor.newInstance(connection);
    }

    private static Throwable invokeSynchronousExecute(ApiConnectionImpl connection, Command command, int timeout) throws Exception {
        Method execute = ApiConnectionImpl.class.getDeclaredMethod("execute", Command.class, Integer.TYPE);
        execute.setAccessible(true);
        try {
            execute.invoke(connection, command, timeout);
            fail("Expected synchronous execute failure");
            return null;
        } catch (InvocationTargetException ex) {
            return ex.getCause();
        }
    }

    private static Throwable invokeAsyncExecute(ApiConnectionImpl connection, Command command,
            ResultListener listener) throws Exception {
        Method execute = ApiConnectionImpl.class.getDeclaredMethod("execute", Command.class, ResultListener.class);
        execute.setAccessible(true);
        try {
            execute.invoke(connection, command, listener);
            fail("Expected asynchronous execute failure");
            return null;
        } catch (InvocationTargetException ex) {
            return ex.getCause();
        }
    }

    private static ApiConnectionImpl newConnection() throws Exception {
        Constructor<ApiConnectionImpl> constructor = ApiConnectionImpl.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static ApiConnectionImpl newConnectedConnection() throws Exception {
        ApiConnectionImpl connection = newConnection();
        Field state = ApiConnectionImpl.class.getDeclaredField("state");
        state.setAccessible(true);
        for (Object constant : state.getType().getEnumConstants()) {
            if ("CONNECTED".equals(constant.toString())) {
                state.set(connection, constant);
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
