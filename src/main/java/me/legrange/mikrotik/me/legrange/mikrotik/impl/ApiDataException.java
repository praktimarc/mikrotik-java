package me.legrange.mikrotik.impl;

/**
 * Compatibility subtype for API data errors exposed by older imports.
 *
 * @author Gideon Le Grange
 */
public class ApiDataException extends me.legrange.mikrotik.ApiDataException {

    ApiDataException(String msg) {
        super(msg);
    }

    ApiDataException(String msg, Throwable err) {
        super(msg, err);
    }
}
