package ddd.Security;

public class SecurityExceptions {
    public static class ClientSessionException extends Exception {
        public ClientSessionException(String errorMessage)
        {
            super(errorMessage);
        }
    }
}
