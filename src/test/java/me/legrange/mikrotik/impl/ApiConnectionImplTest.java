package me.legrange.mikrotik.impl;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import me.legrange.mikrotik.ApiConnectionException;
import me.legrange.mikrotik.MikrotikApiException;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ApiConnectionImplTest {

    @Test(timeout = 1500)
    public void synchronousErrorCompletesImmediatelyAndPreservesOriginalError() throws Exception {
        Class<?> listenerClass = Class.forName("me.legrange.mikrotik.impl.ApiConnectionImpl$SyncListener");
        Constructor<?> constructor = listenerClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object listener = constructor.newInstance();

        Method error = listenerClass.getDeclaredMethod("error", MikrotikApiException.class);
        error.setAccessible(true);
        Method getResults = listenerClass.getDeclaredMethod("getResults", Integer.TYPE);
        getResults.setAccessible(true);

        error.invoke(listener, new MikrotikApiException("unknown parameter"));

        long start = System.nanoTime();
        Throwable thrown = null;
        try {
            getResults.invoke(listener, 1000);
            fail("Expected MikrotikApiException");
        } catch (InvocationTargetException ex) {
            thrown = ex.getCause();
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000L;

        assertTrue("Expected MikrotikApiException", thrown instanceof MikrotikApiException);
        assertEquals("unknown parameter", thrown.getMessage());
        assertTrue("Error handling waited " + elapsedMs + " ms", elapsedMs < 500L);
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
