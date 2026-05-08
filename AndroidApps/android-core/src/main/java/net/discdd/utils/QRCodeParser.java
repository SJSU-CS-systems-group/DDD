package net.discdd.utils;

import android.net.Uri;

/**
 * Parses a DDD server configuration URL from a QR code.
 * Expected format: https://discdd.net/srvr?host=HOST&port=PORT&identity=KEY&signedpre=KEY&ratchet=KEY
 */
public class QRCodeParser {

    public static class ServerConfig {
        public final String host;
        public final int port;
        public final String identity;
        public final String signedPre;
        public final String ratchet;

        public ServerConfig(String host, int port, String identity, String signedPre, String ratchet) {
            this.host = host;
            this.port = port;
            this.identity = identity;
            this.signedPre = signedPre;
            this.ratchet = ratchet;
        }
    }

    /**
     * Parses a QR code URL into a ServerConfig.
     *
     * @param url the scanned URL string
     * @return ServerConfig if the URL is valid, null otherwise
     */
    public static ServerConfig parse(String url) {
        try {
            Uri uri = Uri.parse(url);

            String host = uri.getQueryParameter("host");
            String portStr = uri.getQueryParameter("port");
            String identity = uri.getQueryParameter("identity");
            String signedPre = uri.getQueryParameter("signedpre");
            String ratchet = uri.getQueryParameter("ratchet");

            if (host == null || host.isEmpty() || portStr == null || portStr.isEmpty()) {
                return null;
            }

            int port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) {
                return null;
            }

            return new ServerConfig(host, port, identity, signedPre, ratchet);
        } catch (Exception e) {
            return null;
        }
    }
}
