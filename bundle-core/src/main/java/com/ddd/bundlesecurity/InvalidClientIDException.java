package com.ddd.bundlesecurity;

public class InvalidClientIDException extends Exception {
    public InvalidClientIDException(String message, Throwable throwable) {
        super(message, throwable);
    }
}