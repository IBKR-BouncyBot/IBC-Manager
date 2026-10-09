// IBC Manager integration; GPL-3.0-or-later, like the upstream engine.
package ibcalpha.ibc;

import java.lang.reflect.InvocationTargetException;

/** Loads IBKR's separately installed Gateway, never a packaged substitute. */
final class GatewayEntrypoint {
    private GatewayEntrypoint() { }

    static void invoke(String[] args) throws Throwable {
        try {
            Class<?> entry = Class.forName("ibgateway.GWClient");
            entry.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException ex) {
            // Preserve the original Gateway failure, rather than hiding it in reflection noise.
            throw ex.getCause();
        }
    }
}
