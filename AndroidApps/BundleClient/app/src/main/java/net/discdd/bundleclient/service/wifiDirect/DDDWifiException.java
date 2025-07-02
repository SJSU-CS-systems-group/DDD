package net.discdd.bundleclient.service.wifiDirect;

public class DDDWifiException extends RuntimeException {
    public static class DDDWifiSecurityException extends DDDWifiException {
        public DDDWifiSecurityException(String message, Exception e) {
            super(message, e);
        }
    }
    public static class DDDWifiConnectionException extends DDDWifiException {
        public DDDWifiConnectionException(String message, Exception e) {
            super(message, e);
        }
    }

    public DDDWifiException(String message, Exception e) {
        super(message, e);
    }
}
