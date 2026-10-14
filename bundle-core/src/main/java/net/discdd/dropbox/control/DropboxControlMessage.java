package net.discdd.dropbox.control;

import net.discdd.utils.BundleUtils;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Base of the control messages: {@link InventoryReport} and {@link RetentionPlan} on the {@link DropboxControl#APP_ID}
 * channel, {@link RecencyRequest} and {@link RecencyGrant} on the {@link DropboxControl#RECENCY_APP_ID} channel. It
 * names the message's {@code "type"} discriminator and holds the validation helpers they share.
 *
 * <p>Messages are plain value objects that Gson fills reflectively. Their lists may be null after decoding
 * (absent or explicit {@code null} in JSON), so getters always return a non-null, unmodifiable view. The
 * {@code "type"} and {@code "v"} fields are written and checked by {@link DropboxControlCodec}, not stored here.
 */
public abstract class DropboxControlMessage {
    /** Base64 / Base64url alphabet, with optional padding. Fingerprints use the url alphabet without padding. */
    private static final Pattern BASE64_CHARS = Pattern.compile("^[A-Za-z0-9+/_=-]+$");

    DropboxControlMessage() {}

    /** @return the {@code "type"} discriminator, one of the {@code DropboxControl.TYPE_*} constants */
    public abstract String type();

    /**
     * Checks the contract rules for this message. Called by the codec on both encode and decode, so a
     * producer cannot send what a consumer would reject.
     *
     * @throws DropboxControlException with kind {@code INVALID} or {@code TOO_LARGE}
     */
    abstract void validate() throws DropboxControlException;

    /** @return an unmodifiable view of {@code list}, or an empty list if it is null */
    static <T> List<T> view(List<T> list) {
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    /** @return a defensive copy of {@code list}, or null if it is null (so absent lists stay absent in JSON) */
    static <T> List<T> copyOrNull(List<T> list) {
        return list == null ? null : new ArrayList<>(list);
    }

    /** @return a defensive copy of {@code list}, or an empty list if it is null */
    static <T> List<T> copy(List<T> list) {
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    static void requireNonNegative(long value, String field) throws DropboxControlException {
        if (value < 0) invalid(field + " must be >= 0, was " + value);
    }

    static void requirePositive(long value, String field) throws DropboxControlException {
        if (value <= 0) invalid(field + " must be > 0, was " + value);
    }

    static void requireCount(List<?> list, int max, String field) throws DropboxControlException {
        if (list != null && list.size() > max) {
            throw new DropboxControlException(DropboxControlException.Kind.TOO_LARGE,
                                              field + " has " + list.size() + " entries, max " + max);
        }
    }

    static void requireNoNullElements(List<?> list, String field) throws DropboxControlException {
        if (list == null) return;
        for (Object o : list) {
            if (o == null) invalid(field + " contains a null entry");
        }
    }

    /**
     * Bundle IDs become file names on the dropbox, so they get the same check the transport applies on
     * download ({@code BundleUtils.checkIdClean}: {@code ^[a-zA-Z0-9-_=]+$}, at most 100 characters).
     */
    static void requireBundleId(String bundleId, String field) throws DropboxControlException {
        if (bundleId == null) invalid(field + " is missing");
        try {
            BundleUtils.checkIdClean(bundleId);
        } catch (InvalidParameterException e) {
            invalid(field + " is not a clean bundle ID: " + abbreviate(bundleId));
        }
    }

    /** {@code clientKeyB64} is optional; when present it must look like a Base64 fingerprint of bounded length. */
    static void requireClientKey(String clientKeyB64, String field) throws DropboxControlException {
        if (clientKeyB64 == null) return;
        if (clientKeyB64.isEmpty() || clientKeyB64.length() > DropboxControl.MAX_CLIENT_KEY_CHARS ||
                !BASE64_CHARS.matcher(clientKeyB64).matches()) {
            invalid(field + " is not a Base64 key fingerprint: " + abbreviate(clientKeyB64));
        }
    }

    /**
     * Checks a required standard Base64 field ({@link Base64#getEncoder()}: RFC 4648 alphabet, padding optional on
     * decode) of bounded length that decodes to at least one byte.
     *
     * @return the decoded bytes
     */
    static byte[] requireBase64(String value, int maxChars, String field) throws DropboxControlException {
        if (value == null || value.isEmpty()) invalid(field + " is missing");
        if (value.length() > maxChars) invalid(field + " has " + value.length() + " characters, max " + maxChars);
        byte[] decoded = null;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            invalid(field + " is not standard Base64: " + abbreviate(value));
        }
        if (decoded.length == 0) invalid(field + " decodes to no bytes");
        return decoded;
    }

    static void invalid(String message) throws DropboxControlException {
        throw new DropboxControlException(DropboxControlException.Kind.INVALID, message);
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...(" + s.length() + " chars)";
    }
}
