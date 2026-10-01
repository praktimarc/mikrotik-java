package me.legrange.mikrotik.impl;

/**
 * Compatibility subtype for command errors exposed by older imports.
 *
 * @author Gideon Le Grange
 */
public class ApiCommandException extends me.legrange.mikrotik.ApiCommandException {

    ApiCommandException(String msg) {
        super(msg, null, null);
    }

    ApiCommandException(String msg, Throwable err) {
        super(msg, null, null, err);
    }

    ApiCommandException(Error err) {
        super(err.getMessage(), err.getTag(), err.hasCategory() ? Integer.valueOf(err.getCategory()) : null);
    }
}
