package me.legrange.mikrotik;

/**
 * Thrown when RouterOS API data is malformed or inconsistent.
 */
public class ApiDataException extends MikrotikApiException {

    protected ApiDataException(String message) {
        super(message);
    }

    protected ApiDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
