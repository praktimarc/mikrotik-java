package me.legrange.mikrotik.impl;

import me.legrange.mikrotik.MikrotikApiException;

/**
 * Internal listener for binary RouterOS command results.
 */
interface BinaryResultListener {

    void receive(byte[] data);

    void error(MikrotikApiException ex);

    void completed();
}
