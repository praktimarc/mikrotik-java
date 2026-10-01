package me.legrange.mikrotik;

import java.util.Map;

/**
 * Implement this interface to receive command results from the Mikrotik Api.
 * @author GideonLeGrange
 */
public interface ResultListener {
    
    /** receive data from router
     * @param result The data received */
    void receive(Map<String, String> result);

    /** called if the command associated with this listener experiences an error
     * @param ex Exception encountered */
    void error(MikrotikApiException ex);
    
    /** called when the command associated with this listener is done */
    void completed();

    /**
     * Called when the command associated with this listener is done, with all
     * terminal properties supplied by the RouterOS {@code !done} sentence.
     *
     * <p>The default implementation delegates to {@link #completed()} so
     * existing listeners continue to work unchanged. Implementations that need
     * terminal metadata can override this method instead.</p>
     *
     * @param completion unmodifiable map of terminal RouterOS properties;
     * empty when {@code !done} contains no properties
     */
    default void completed(Map<String, String> completion) {
        completed();
    }
   
}
