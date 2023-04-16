package com.ddd.server.bundlerouting.WindowUtils;

public class WindowExceptions {
    public static class InvalidLength extends Exception {
        public InvalidLength(String errorMessage)
        {
            super(errorMessage);
        }
    }

    public static class BufferOverflow extends Exception {
        public BufferOverflow(String errorMessage)
        {
            super(errorMessage);
        }
    }

    public static class BufferUnderflow extends Exception {
        public BufferUnderflow(String errorMessage)
        {
            super(errorMessage);
        }
    }

    public static class ItemNotFound extends Exception {
        public ItemNotFound(String errorMessage)
        {
            super(errorMessage);
        }
    }

    public static class RecievedOldACK extends Exception {
        public RecievedOldACK(String errorMessage)
        {
            super(errorMessage);
        }
    }

    public static class RecievedInvalidACK extends Exception {
        public RecievedInvalidACK(String errorMessage)
        {
            super(errorMessage);
        }
    }

    public static class ClientNotFound extends Exception {
        public ClientNotFound(String errorMessage)
        {
            super(errorMessage);
        }
    }

    public static class ClientAlreadyExists extends Exception {
        public ClientAlreadyExists(String errorMessage)
        {
            super(errorMessage);
        }
    }

    public static class InvalidBundleID extends Exception {
        public InvalidBundleID(String errorMessage)
        {
            super(errorMessage);
        }
    }
}