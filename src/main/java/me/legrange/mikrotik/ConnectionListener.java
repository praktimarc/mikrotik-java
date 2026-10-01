package me.legrange.mikrotik;

/**
 * Listener notified when an established API connection is lost unexpectedly.
 */
@FunctionalInterface
public interface ConnectionListener {

    /**
     * Called once when the connection enters a fatal failed state.
     *
     * @param cause retained connection failure that terminated the session
     */
    void connectionLost(ApiConnectionException cause);
}
