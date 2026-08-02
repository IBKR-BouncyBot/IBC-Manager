package io.github.ibcmanager.runtime;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

public final class TcpPortProbe implements PortProbe {
    @Override
    public boolean isOpen(String host, int port, Duration timeout) {
        if (port < 1 || port > 65535) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Math.toIntExact(timeout.toMillis()));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
