package me.legrange.mikrotik;

/**
 * Public exception type for malformed, inconsistent, or otherwise unusable
 * RouterOS API data.
 *
 * <p>This type is used when the API transport remains distinguishable from a
 * command-level RouterOS error but the received data cannot be interpreted
 * safely. Unrecoverable session/protocol failures are surfaced as
 * {@link ApiConnectionException}, with the data error retained as a cause when
 * applicable.</p>
 */
public class ApiDataException extends MikrotikApiException {

    protected ApiDataException(String message) {
        super(message);
    }

    protected ApiDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
