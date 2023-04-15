package com.ddd.server.bundlesecurity;

public class SecurityExceptions {
    public static class ClientSessionException extends Exception {
        public ClientSessionException(String errorMessage)
        {
            super(errorMessage);
        }
    }
}
