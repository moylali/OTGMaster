package app.fayaz.otgmaster.spike;

import android.os.ParcelFileDescriptor;

/** Debug-only: see FdRelaySpike for what this is proving. */
interface IFdRelay {
    /**
     * Creates a ProxyFileDescriptor in THIS process and returns it to the caller.
     * mode is "r" or "rw". keepOpen=false makes the worker close its own copy
     * immediately after returning, which is the lifetime question.
     */
    ParcelFileDescriptor openProxy(String mode, boolean keepOpen);

    /** Process id of this service, so the caller can confirm where callbacks ran. */
    int servicePid();

    /** Where each callback observed itself running, and in what order. */
    List<String> callbackLog();

    /** SHA-256 of the content the worker is serving, for an end-to-end check. */
    String expectedSha256();

    /** Drops the worker's retained PFD, if openProxy was called with keepOpen. */
    void releaseRetained();
}
