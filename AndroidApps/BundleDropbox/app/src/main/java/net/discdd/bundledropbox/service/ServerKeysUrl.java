package net.discdd.bundledropbox.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A DDD server's QR-code URL, as shown by the bundleserver's {@code /qr} page ({@code QRCodeController}) and scanned
 * by the stock BundleClient: {@code https://discdd.net/srvr?host=H&port=P&identity=K&signedpre=K&ratchet=K}, where
 * each {@code K} is the base64url middle line of the server's public-key PEM file (Research/R7 §1).
 *
 * <p>The dropbox uses only the three keys: they become its trusted server keys ({@link ServerKeys#saveImported}),
 * which pin the recency blobs it relays and seed its control endpoint's Signal session. Host and port are kept only
 * as an operator-facing label; the dropbox never connects to the server.
 *
 * <p>Pure Java (no {@code android.net.Uri}, unlike android-core's {@code QRCodeParser}), so it is JVM-tested and
 * also accepts the same URL pasted as text on a dropbox without a camera. Stricter than BundleClient on purpose:
 * all three keys are required, since they are the only reason to import. The key values are only checked for
 * presence here; {@link ServerKeys#saveImported} decodes them.
 */
public final class ServerKeysUrl {
    private final String host;
    private final Integer port;
    private final String identity;
    private final String signedPre;
    private final String ratchet;

    private ServerKeysUrl(String host, Integer port, String identity, String signedPre, String ratchet) {
        this.host = host;
        this.port = port;
        this.identity = identity;
        this.signedPre = signedPre;
        this.ratchet = ratchet;
    }

    /**
     * Parses a scanned or pasted server URL.
     *
     * @param text the QR payload or pasted text (surrounding whitespace is ignored)
     * @return the parsed URL
     * @throws IllegalArgumentException with an operator-readable message if it is not a server-keys URL
     */
    public static ServerKeysUrl parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Nothing to import");
        String t = text.trim();
        int q = t.indexOf('?');
        if (q < 0 || q == t.length() - 1) {
            throw new IllegalArgumentException("Not a DDD server URL (expected https://discdd.net/srvr?host=…)");
        }
        String query = t.substring(q + 1);
        int fragment = query.indexOf('#');
        if (fragment >= 0) query = query.substring(0, fragment);
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            // A key's value may itself end in '=' padding, so only the first '=' separates name and value.
            params.putIfAbsent(pair.substring(0, eq), decode(pair.substring(eq + 1)));
        }
        String identity = required(params, "identity");
        String signedPre = required(params, "signedpre");
        String ratchet = required(params, "ratchet");
        String host = params.get("host");
        Integer port = null;
        String portText = params.get("port");
        if (portText != null && !portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad port in server URL: " + portText);
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Bad port in server URL: " + port);
        }
        return new ServerKeysUrl(host == null || host.isEmpty() ? null : host, port, identity, signedPre, ratchet);
    }

    /** @return the server host named in the URL, or {@code null} */
    public String host() { return host; }

    /** @return the server port named in the URL, or {@code null} */
    public Integer port() { return port; }

    /** @return base64url server identity public key */
    public String identity() { return identity; }

    /** @return base64url server signed pre-key */
    public String signedPre() { return signedPre; }

    /** @return base64url server ratchet key */
    public String ratchet() { return ratchet; }

    /** @return {@code host:port}, {@code host}, or {@code null} if the URL named no host */
    public String origin() {
        if (host == null) return null;
        return port == null ? host : host + ":" + port;
    }

    private static String required(Map<String, String> params, String name) {
        String v = params.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Server URL has no '" + name + "' key");
        }
        return v.trim();
    }

    /** Percent-decodes a query value; '+' is kept literally (base64url never contains it, but be strict). */
    private static String decode(String v) {
        try {
            return URLDecoder.decode(v.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Bad escape in server URL value: " + v);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ServerKeysUrl)) return false;
        ServerKeysUrl u = (ServerKeysUrl) o;
        return Objects.equals(host, u.host) && Objects.equals(port, u.port) && identity.equals(u.identity) &&
                signedPre.equals(u.signedPre) && ratchet.equals(u.ratchet);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, identity, signedPre, ratchet);
    }

    @Override
    public String toString() {
        return "ServerKeysUrl{" + origin() + "}";
    }
}
