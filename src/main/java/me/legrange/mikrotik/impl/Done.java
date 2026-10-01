package me.legrange.mikrotik.impl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal representation of !done
 * @author GideonLeGrange
 */
class Done extends Response {

    Done(String tag) {
        super(tag);
    }

    void put(String name, String value) {
        properties.put(name, value);
    }

    Map<String, String> getProperties() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    void setHash(String hash) {
        put("ret", hash);
    }
    
    String getHash() {
        return properties.get("ret");
    }

    private final Map<String, String> properties = new LinkedHashMap<>();
    
}
