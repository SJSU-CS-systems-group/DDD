package net.discdd.client.bundletransmission;

import java.io.IOException;

public class ServerKeyMismatchException extends IOException {
    public ServerKeyMismatchException(String message) {
        super(message);
    }

    public ServerKeyMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
