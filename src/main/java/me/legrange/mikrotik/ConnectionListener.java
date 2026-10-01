package me.legrange.mikrotik;

/**
 * Listener notified when an established API connection is lost unexpectedly.
 *
 * <p>The listener is for fatal connection/session loss only. An intentional
 * {@link ApiConnection#close()} does not trigger this callback.</p>
 */
@FunctionalInterface
public interface ConnectionListener {

    /**
     * Called once when the connection enters a fatal failed state.
     *
     * <p>The same retained failure is also used to terminate active commands and
     * to explain later submissions to the failed connection.</p>
     *
     * @param cause retained connection failure that terminated the session
     */
    void connectionLost(ApiConnectionException cause);
}
