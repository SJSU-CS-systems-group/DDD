package com.ddd.server.bundlesecurity;

public class InvalidClientSessionException extends Exception {
    public InvalidClientSessionException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
