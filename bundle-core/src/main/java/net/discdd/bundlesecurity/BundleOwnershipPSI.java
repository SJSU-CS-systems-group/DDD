package net.discdd.bundlesecurity;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements a Diffie-Hellman style Private Set Intersection (PSI) protocol
 * for obscuring bundle ownership between client and transport.
 *
 * <p>The protocol allows a client to check whether a transport holds a specific
 * bundle without revealing which bundle ID the client is looking for.
 *
 * <p>Uses RFC 3526 Group 15 (3072-bit MODP safe prime) for modular arithmetic.
 */
public class BundleOwnershipPSI {

    // RFC 3526 Group 15: 3072-bit MODP safe prime
    public static final BigInteger MODP_PRIME = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" + "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                    "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                    "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
                    "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                    "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                    "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
                    "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                    "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
                    "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
                    "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                    "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
                    "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16);

    // q = (p - 1) / 2 — the order of the quadratic residue subgroup
    public static final BigInteger SUBGROUP_ORDER = MODP_PRIME.subtract(BigInteger.ONE).divide(BigInteger.TWO);

    private final SecureRandom secureRandom;

    public BundleOwnershipPSI() {
        this.secureRandom = new SecureRandom();
    }

    // Package-private for testing with deterministic randomness
    BundleOwnershipPSI(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    /**
     * Generate a random secret exponent in [2, q-1].
     */
    public BigInteger generateSecret() {
        BigInteger secret;
        do {
            secret = new BigInteger(SUBGROUP_ORDER.bitLength(), secureRandom);
        } while (secret.compareTo(BigInteger.TWO) < 0 || secret.compareTo(SUBGROUP_ORDER) >= 0);
        return secret;
    }

    /**
     * Hash a bundle ID string to a group element in the quadratic residue subgroup.
     * Uses SHA-256, then squares mod p to ensure subgroup membership.
     */
    public static BigInteger hashToGroup(String bundleId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bundleId.getBytes(StandardCharsets.UTF_8));
            BigInteger h = new BigInteger(1, hash).mod(MODP_PRIME);
            if (h.equals(BigInteger.ZERO)) {
                h = BigInteger.ONE;
            }
            return h.modPow(BigInteger.TWO, MODP_PRIME);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Blind a value: compute value^exponent mod p.
     */
    public static BigInteger blind(BigInteger value, BigInteger exponent) {
        return value.modPow(exponent, MODP_PRIME);
    }

    /**
     * Client step 1: for each bundle ID, compute H(bundleID)^a mod p.
     */
    public List<BigInteger> clientBlindBundleIds(List<String> bundleIds, BigInteger clientSecret) {
        List<BigInteger> blinded = new ArrayList<>(bundleIds.size());
        for (String id : bundleIds) {
            blinded.add(blind(hashToGroup(id), clientSecret));
        }
        return blinded;
    }

    /**
     * Client step 2: find matches between the client's blinded values and transport's values.
     *
     * @param doublyBlindedClientValues x_j^b values from transport (one per client query)
     * @param transportBlindedValues    y_i values from transport (one per transport bundle)
     * @param clientSecret              the client's secret exponent a
     * @return list of matches as (transportIndex, clientIndex) pairs
     */
    public List<PSIMatch> clientFindMatches(List<BigInteger> doublyBlindedClientValues,
                                            List<BigInteger> transportBlindedValues,
                                            BigInteger clientSecret) {
        // doublyBlindedClientValues are already (x_j^b) = H(bundleID_j)^(ab), no further blinding needed

        // Compute y_i^a = H(fileName_i)^(ab) for each transport value and index by value
        Map<BigInteger, List<Integer>> transportIndex = new HashMap<>();
        for (int i = 0; i < transportBlindedValues.size(); i++) {
            BigInteger val = blind(transportBlindedValues.get(i), clientSecret);
            transportIndex.computeIfAbsent(val, k -> new ArrayList<>()).add(i);
        }

        // Find matching pairs via hash lookup
        List<PSIMatch> matches = new ArrayList<>();
        for (int j = 0; j < doublyBlindedClientValues.size(); j++) {
            List<Integer> hits = transportIndex.get(doublyBlindedClientValues.get(j));
            if (hits != null) {
                for (int i : hits) {
                    matches.add(new PSIMatch(i, j));
                }
            }
        }
        return matches;
    }

    /**
     * Transport step: blind client values and own bundle file names.
     *
     * @param clientBlindedValues x_j values from client
     * @param bundleFileNames     file names of bundles held by transport
     * @param transportSecret     the transport's secret exponent b
     */
    public TransportPSIResponse transportProcess(List<BigInteger> clientBlindedValues,
                                                 List<String> bundleFileNames,
                                                 BigInteger transportSecret) {
        List<BigInteger> doublyBlindedClient = new ArrayList<>(clientBlindedValues.size());
        for (BigInteger val : clientBlindedValues) {
            doublyBlindedClient.add(blind(val, transportSecret));
        }

        List<BigInteger> transportBlinded = new ArrayList<>(bundleFileNames.size());
        for (String fileName : bundleFileNames) {
            transportBlinded.add(blind(hashToGroup(fileName), transportSecret));
        }

        return new TransportPSIResponse(doublyBlindedClient, transportBlinded);
    }

    public record PSIMatch(int transportIndex, int clientIndex) {}

    public record TransportPSIResponse(List<BigInteger> doublyBlindedClientValues,
                                       List<BigInteger> transportBlindedValues) {}
}
