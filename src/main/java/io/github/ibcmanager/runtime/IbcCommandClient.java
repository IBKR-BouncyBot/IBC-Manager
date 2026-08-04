package io.github.ibcmanager.runtime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class IbcCommandClient implements CommandClient {
    static final int MAX_RESPONSE_CHARACTERS = 64 * 1024;
    static final int MAX_LINE_CHARACTERS = 8 * 1024;
    static final int MAX_RESPONSE_LINES = 1024;

    public IbcCommandResult send(String host, int port, IbcCommand command, Duration timeout) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        if (host.isBlank()) throw new IllegalArgumentException("host must not be blank");
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("port must be between 1 and 65535");
        long timeoutValue = timeout.toMillis();
        if (timeoutValue < 1 || timeoutValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("timeout must be between 1 ms and " + Integer.MAX_VALUE + " ms");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), remainingMillis(deadline));
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                            socket.getOutputStream(), StandardCharsets.US_ASCII));
                    InputStreamReader reader = new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII)) {
                writer.write(command.name());
                writer.newLine();
                writer.write("EXIT");
                writer.newLine();
                writer.flush();

                StringBuilder response = new StringBuilder();
                StringBuilder line = new StringBuilder();
                CommandDisposition disposition = CommandDisposition.UNKNOWN;
                int lines = 0;
                boolean previousCarriageReturn = false;
                boolean goodbye = false;
                try {
                    while (!goodbye) {
                        socket.setSoTimeout(remainingMillis(deadline));
                        int value = reader.read();
                        if (value < 0) {
                            if (!line.isEmpty()) {
                                LineOutcome outcome = finishLine(response, line, disposition);
                                disposition = outcome.disposition();
                            }
                            break;
                        }
                        char character = (char) value;
                        if (character == '\r') {
                            LineOutcome outcome = finishLine(response, line, disposition);
                            disposition = outcome.disposition();
                            goodbye = outcome.goodbye();
                            lines++;
                            previousCarriageReturn = true;
                        } else if (character == '\n') {
                            if (!previousCarriageReturn) {
                                LineOutcome outcome = finishLine(response, line, disposition);
                                disposition = outcome.disposition();
                                goodbye = outcome.goodbye();
                                lines++;
                            }
                            previousCarriageReturn = false;
                        } else {
                            previousCarriageReturn = false;
                            if (line.length() >= MAX_LINE_CHARACTERS) {
                                throw new IOException("IBC command-server response line exceeds the safety limit");
                            }
                            line.append(character);
                        }
                        if (response.length() + line.length() > MAX_RESPONSE_CHARACTERS) {
                            throw new IOException("IBC command-server response exceeds the safety limit");
                        }
                        if (lines > MAX_RESPONSE_LINES) {
                            throw new IOException("IBC command-server response contains too many lines");
                        }
                    }
                } catch (SocketTimeoutException ex) {
                    if (response.isEmpty() && line.isEmpty()) throw ex;
                    if (!line.isEmpty()) {
                        LineOutcome outcome = finishLine(response, line, disposition);
                        disposition = outcome.disposition();
                    }
                }
                return new IbcCommandResult(disposition, response.toString());
            }
        }
    }

    private static LineOutcome finishLine(StringBuilder response, StringBuilder line,
            CommandDisposition currentDisposition) throws IOException {
        String value = line.toString();
        line.setLength(0);
        if (response.length() > 0) response.append('\n');
        response.append(value);
        if (response.length() > MAX_RESPONSE_CHARACTERS) {
            throw new IOException("IBC command-server response exceeds the safety limit");
        }
        CommandDisposition result = currentDisposition;
        boolean goodbye = value.equalsIgnoreCase("OK Goodbye");
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("ERROR") || upper.startsWith("ERROR ")) {
            // A later NACK must override a preliminary "OK ... in progress" acknowledgement.
            result = CommandDisposition.REJECTED;
        } else if ((upper.equals("OK") || upper.startsWith("OK ")) && !goodbye
                && result != CommandDisposition.REJECTED) {
            result = upper.contains(" IN PROGRESS")
                    ? CommandDisposition.ACCEPTED : CommandDisposition.COMPLETED;
        }
        return new LineOutcome(result, goodbye);
    }

    private record LineOutcome(CommandDisposition disposition, boolean goodbye) { }

    private static int remainingMillis(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("IBC command-server operation timed out");
        long millis = Math.max(1L, Math.min(Integer.MAX_VALUE, (remaining + 999_999L) / 1_000_000L));
        return (int) millis;
    }
}
