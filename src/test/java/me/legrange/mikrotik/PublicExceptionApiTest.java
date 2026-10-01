package me.legrange.mikrotik;

import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PublicExceptionApiTest {

    @Test
    public void publicExceptionHierarchyIsStable() {
        assertTrue(MikrotikApiException.class.isAssignableFrom(ApiCommandException.class));
        assertTrue(MikrotikApiException.class.isAssignableFrom(ApiDataException.class));
        assertTrue(ApiCommandException.class.isAssignableFrom(me.legrange.mikrotik.impl.ApiCommandException.class));
        assertTrue(ApiDataException.class.isAssignableFrom(me.legrange.mikrotik.impl.ApiDataException.class));
    }

    @Test
    public void resultListenerCompletionMetadataHookIsDefaultMethod() throws Exception {
        final Method method;
        try {
            method = ResultListener.class.getMethod("completed", Map.class);
        } catch (NoSuchMethodException ex) {
            fail("ResultListener must expose completed(Map<String, String>)");
            return;
        }

        assertTrue("Completion metadata hook must be a default method", method.isDefault());
    }
}
