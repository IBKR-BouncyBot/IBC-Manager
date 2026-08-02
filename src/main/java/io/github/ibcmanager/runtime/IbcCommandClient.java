package io.github.ibcmanager.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class IbcCommandClient implements CommandClient {
    public IbcCommandResult send(String host, int port, IbcCommand command, Duration timeout) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        long timeoutValue = timeout.toMillis();
        if (timeoutValue < 1 || timeoutValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("timeout must be between 1 ms and " + Integer.MAX_VALUE + " ms");
        }
        int timeoutMillis = (int) timeoutValue;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(command.name());
                writer.newLine();
                writer.write("EXIT");
                writer.newLine();
                writer.flush();

                StringBuilder response = new StringBuilder();
                Boolean commandSuccess = null;
                String line;
                try {
                    while ((line = reader.readLine()) != null) {
                        if (response.length() > 0) response.append('\n');
                        response.append(line);
                        if (line.startsWith("OK ") && !line.equalsIgnoreCase("OK Goodbye") && commandSuccess == null) commandSuccess = true;
                        if (line.startsWith("ERROR ") && commandSuccess == null) commandSuccess = false;
                        if (line.equalsIgnoreCase("OK Goodbye")) break;
                    }
                } catch (java.net.SocketTimeoutException ex) {
                    if (response.length() == 0) throw ex;
                }
                return new IbcCommandResult(Boolean.TRUE.equals(commandSuccess), response.toString());
            }
        }
    }
}
