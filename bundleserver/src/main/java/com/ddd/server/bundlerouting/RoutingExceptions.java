package com.ddd.server.bundlerouting;

public class RoutingExceptions {
    public static class ClientMetaDataFileException extends Exception {
        public ClientMetaDataFileException(String errorMessage) {
            super(errorMessage);
        }
    }

    public static class TransportIdNotFoundException extends Exception {
        public TransportIdNotFoundException(String errorMessage) {
            super(errorMessage);
        }
    }
}
