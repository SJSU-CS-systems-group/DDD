package com.ddd.client.bundlerouting.WindowUtils;

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
}