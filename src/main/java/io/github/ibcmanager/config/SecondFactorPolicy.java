package io.github.ibcmanager.config;

/** Central unattended-login policy for IBKR Mobile second-factor retries. */
public final class SecondFactorPolicy {
    /**
     * The maintained engine arms an independent monotonic deadline when it observes
     * a second-factor challenge. The timeout initiates relogin, not phone delivery.
     */
    public static final int RETRY_TIMEOUT_SECONDS = 5 * 60;

    /** IBC's documented default when automatic retry is disabled. */
    public static final int IBC_DEFAULT_TIMEOUT_SECONDS = 180;

    private SecondFactorPolicy() {
    }

    public static String configuredTimeout(boolean automaticRetryEnabled) {
        return Integer.toString(automaticRetryEnabled
                ? RETRY_TIMEOUT_SECONDS
                : IBC_DEFAULT_TIMEOUT_SECONDS);
    }
}
