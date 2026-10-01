package me.legrange.mikrotik.impl;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import me.legrange.mikrotik.ApiConnectionException;
import me.legrange.mikrotik.MikrotikApiException;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ApiConnectionImplTest {

    @Test(timeout = 1500)
    public void synchronousErrorCompletesImmediatelyAndPreservesOriginalError() throws Exception {
        Object listener = newTextListener();
        Method error = textMethod("error", MikrotikApiException.class);

        error.invoke(listener, new MikrotikApiException("unknown parameter"));

        long start = System.nanoTime();
        Throwable thrown = getTextFailure(listener, 1000);
        long elapsedMs = (System.nanoTime() - start) / 1000000L;

        assertTrue("Expected MikrotikApiException", thrown instanceof MikrotikApiException);
        assertEquals("unknown parameter", thrown.getMessage());
        assertTrue("Error handling waited " + elapsedMs + " ms", elapsedMs < 500L);
    }

    @Test
    public void synchronousTextListenerPreservesConcreteErrorType() throws Exception {
        Object listener = newTextListener();
        ApiConnectionException expected = new ApiConnectionException("connection lost");

        textMethod("error", MikrotikApiException.class).invoke(listener, expected);

        Throwable thrown = getTextFailure(listener, 1000);

        assertSame(expected, thrown);
    }

    @Test
    public void commandExceptionExposesStructuredMetadataIncludingCategoryPresence() {
        Error withCategory = new Error();
        withCategory.setTag("a1");
        withCategory.setMessage("bad command");
        withCategory.setCategory(5);

        me.legrange.mikrotik.ApiCommandException commandError = new ApiCommandException(withCategory);
        assertEquals("a1", commandError.getTag());
        assertEquals("bad command", commandError.getMessage());
        assertEquals(5, commandError.getCategory());
        assertTrue(commandError.hasCategory());

        Error zeroCategory = new Error();
        zeroCategory.setTag("a2");
        zeroCategory.setMessage("zero category");
        zeroCategory.setCategory(0);

        me.legrange.mikrotik.ApiCommandException zeroError = new ApiCommandException(zeroCategory);
        assertEquals(0, zeroError.getCategory());
        assertTrue(zeroError.hasCategory());

        Error noCategory = new Error();
        noCategory.setTag("a3");
        noCategory.setMessage("no category");

        me.legrange.mikrotik.ApiCommandException absentError = new ApiCommandException(noCategory);
        assertEquals(0, absentError.getCategory());
        assertFalse(absentError.hasCategory());
    }

    @Test
    public void publicDataExceptionCompatibilitySubclassPreservesCause() {
        IllegalArgumentException cause = new IllegalArgumentException("bad payload");
        me.legrange.mikrotik.ApiDataException error = new ApiDataException("invalid data", cause);

        assertEquals("invalid data", error.getMessage());
        assertSame(cause, error.getCause());
    }

    @Test
    public void synchronousBinaryListenerPreservesPayload() throws Exception {
        Object listener = newBinaryListener();
        byte[] payload = new byte[]{0, 1, (byte) 0xff, 13, 10};

        binaryMethod("receive", byte[].class).invoke(listener, (Object) payload);
        binaryMethod("completed").invoke(listener);

        assertArrayEquals(payload, getBinaryResult(listener, 1000));
    }

    @Test(timeout = 1500)
    public void synchronousBinaryErrorCompletesImmediatelyAndPreservesOriginalError() throws Exception {
        Object listener = newBinaryListener();
        binaryMethod("error", MikrotikApiException.class)
                .invoke(listener, new MikrotikApiException("binary failure"));

        long start = System.nanoTime();
        Throwable thrown = getBinaryFailure(listener, 1000);
        long elapsedMs = (System.nanoTime() - start) / 1000000L;

        assertTrue(thrown instanceof MikrotikApiException);
        assertEquals("binary failure", thrown.getMessage());
        assertTrue("Error handling waited " + elapsedMs + " ms", elapsedMs < 500L);
    }

    @Test(timeout = 1000)
    public void synchronousBinaryListenerTimesOut() throws Exception {
        Throwable thrown = getBinaryFailure(newBinaryListener(), 50);

        assertTrue(thrown instanceof ApiConnectionException);
        assertTrue(thrown.getMessage().contains("timed out"));
    }

    @Test
    public void synchronousBinaryListenerRejectsMultipleDataResults() throws Exception {
        Object listener = newBinaryListener();
        Method receive = binaryMethod("receive", byte[].class);
        receive.invoke(listener, (Object) new byte[]{1});
        receive.invoke(listener, (Object) new byte[]{2});

        Throwable thrown = getBinaryFailure(listener, 1000);

        assertTrue(thrown instanceof ApiDataException);
        assertTrue(thrown.getMessage().contains("multiple"));
    }

    @Test
    public void synchronousBinaryListenerRejectsCompletionWithoutData() throws Exception {
        Object listener = newBinaryListener();
        binaryMethod("completed").invoke(listener);

        Throwable thrown = getBinaryFailure(listener, 1000);

        assertTrue(thrown instanceof ApiDataException);
        assertTrue(thrown.getMessage().contains("without data"));
    }

    private static Object newTextListener() throws Exception {
        Constructor<?> constructor = textListenerClass().getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Method textMethod(String name, Class<?>... parameterTypes) throws Exception {
        Method method = textListenerClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static Throwable getTextFailure(Object listener, int timeout) throws Exception {
        try {
            textMethod("getResults", Integer.TYPE).invoke(listener, timeout);
            fail("Expected text listener failure");
            return null;
        } catch (InvocationTargetException ex) {
            return ex.getCause();
        }
    }

    private static Class<?> textListenerClass() throws ClassNotFoundException {
        return Class.forName("me.legrange.mikrotik.impl.ApiConnectionImpl$SyncListener");
    }

    private static Object newBinaryListener() throws Exception {
        Constructor<?> constructor = binaryListenerClass().getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Method binaryMethod(String name, Class<?>... parameterTypes) throws Exception {
        Method method = binaryListenerClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static byte[] getBinaryResult(Object listener, int timeout) throws Exception {
        Method method = binaryMethod("getResult", Integer.TYPE);
        return (byte[]) method.invoke(listener, timeout);
    }

    private static Throwable getBinaryFailure(Object listener, int timeout) throws Exception {
        try {
            getBinaryResult(listener, timeout);
            fail("Expected binary listener failure");
            return null;
        } catch (InvocationTargetException ex) {
            return ex.getCause();
        }
    }

    private static Class<?> binaryListenerClass() throws ClassNotFoundException {
        return Class.forName("me.legrange.mikrotik.impl.ApiConnectionImpl$SyncBinaryListener");
    }
}
