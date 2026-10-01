package me.legrange.mikrotik;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PublicExceptionApiTest {

    @Test
    public void publicExceptionHierarchyIsStable() {
        assertTrue(MikrotikApiException.class.isAssignableFrom(ApiCommandException.class));
        assertTrue(MikrotikApiException.class.isAssignableFrom(ApiDataException.class));
        assertTrue(ApiCommandException.class.isAssignableFrom(me.legrange.mikrotik.impl.ApiCommandException.class));
        assertTrue(ApiDataException.class.isAssignableFrom(me.legrange.mikrotik.impl.ApiDataException.class));
    }
}
