// IBC Manager integration; GPL-3.0-or-later, like the upstream engine.
package ibcalpha.ibc;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;

/** Report a server rejection, including the untitled dialog, without clicking OK or retrying login. */
public final class InvalidTwsInfoDialogHandler implements WindowHandler {
    private final Map<Window, Boolean> reported = new WeakHashMap<>();

    @Override public boolean filterEvent(Window window, int eventId) {
        return eventId == WindowEvent.WINDOW_OPENED || eventId == WindowEvent.WINDOW_ACTIVATED;
    }

    @Override public void handleWindow(Window window, int eventId) {
        if (!filterEvent(window, eventId) || !recogniseWindow(window) || reported.put(window, Boolean.TRUE) != null) return;
        if (LoginManager.loginManager() != null) LoginManager.loginManager().setLoginState(LoginManager.LoginState.LOGIN_FAILED);
        EngineEvents.emit("LOGIN_REJECTED_TWSINFO");
        Utils.logToConsole("Gateway/TWS login rejected: invalid twsInfo. Review the runtime and application logs; automatic retry was not requested.");
    }

    @Override public boolean recogniseWindow(Window window) {
        return window instanceof JDialog && containsRejection(window);
    }

    static boolean containsRejection(Container root) {
        ArrayDeque<Component> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        boolean message = false, ok = false;
        while (!queue.isEmpty() && ++visited <= 512) {
            Component component = queue.removeFirst();
            if (component instanceof JLabel label) message |= isMessage(label.getText());
            // Only non-editable display text: never inspect username/password inputs.
            if (component instanceof JTextComponent text && !text.isEditable() && text.getDocument().getLength() <= 4096) message |= isMessage(text.getText());
            if (component instanceof JButton button && "OK".equalsIgnoreCase(button.getText())) ok = true;
            if (component instanceof Container container) {
                if (queue.size() + container.getComponentCount() > 512) return false;
                for (Component child : container.getComponents()) queue.addLast(child);
            }
        }
        return queue.isEmpty() && message && ok;
    }

    static boolean isMessage(String text) {
        if (text == null || text.length() > 4096) return false;
        String plain = text.replaceAll("<[^>]*>", "").replace("&nbsp;", " ")
                .replaceAll("[\\s\\u00a0]+", "").toLowerCase(Locale.ROOT);
        return plain.equals("invalidtwsinfo");
    }
}
