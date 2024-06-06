package com.ddd.server.bundlesecurity;

public class SecurityExceptions {
    public static class InvalidClientSessionException extends Exception {
        public InvalidClientSessionException(String errorMessage, Throwable throwable) {
            super(errorMessage, throwable);
        }
    }

    public static class IDGenerationException extends Exception {
        public IDGenerationException(String errorMessage, Throwable throwable) {
            super(errorMessage, throwable);
        }
    }

    public static class EncodingException extends Exception {
        public EncodingException(String errorMessage, Throwable throwable) {
            super(errorMessage, throwable);
        }
    }

    public static class InvalidClientIDException extends Exception {
        public InvalidClientIDException(String errorMessage, Throwable throwable) {
            super(errorMessage, throwable);
        }
    }

    public static class SignatureVerificationException extends Exception {
        public SignatureVerificationException(String errorMessage, Throwable throwable) {
            super(errorMessage, throwable);
        }
    }

    public static class AESAlgorithmException extends Exception {
        public AESAlgorithmException(String errorMessage, Throwable throwable) {
            super(errorMessage, throwable);
        }
    }

    public static class ServerIntializationException extends Exception {
        public ServerIntializationException(String errorMessage, Throwable throwable) {
            super(errorMessage, throwable);
        }
    }

    public static class BundleIDCryptographyException extends Exception {
        public BundleIDCryptographyException(String errorMessage, Throwable throwable) {
            super(errorMessage, throwable);
        }
    }

    public static class BundleDecryptionException extends Exception {
        public BundleDecryptionException(String errorMessage, Throwable throwable) {
            super(errorMessage, throwable);
        }
    }
}
