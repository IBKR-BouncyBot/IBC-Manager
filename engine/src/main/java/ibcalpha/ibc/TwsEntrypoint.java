// IBC Manager integration; GPL-3.0-or-later, like the upstream engine.
package ibcalpha.ibc;

import java.lang.reflect.InvocationTargetException;

/** Loads IBKR's separately installed TWS, never a packaged substitute. */
final class TwsEntrypoint {
    private TwsEntrypoint() { }

    static void invoke(String[] args) throws Throwable {
        try {
            Class<?> entry = Class.forName("jclient.LoginFrame");
            entry.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException ex) {
            // Preserve the original TWS failure, rather than hiding it in reflection noise.
            throw ex.getCause();
        }
    }
}
