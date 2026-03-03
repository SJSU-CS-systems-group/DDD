package net.discdd.bundlesecurity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BundleOwnershipPSITest {

    private BundleOwnershipPSI psi;

    @BeforeEach
    void setUp() {
        psi = new BundleOwnershipPSI();
    }

    @Test
    void testHashToGroupProducesSubgroupElement() {
        BigInteger h = BundleOwnershipPSI.hashToGroup("testBundleId");
        // h^q mod p should equal 1 (Euler's criterion for quadratic residue)
        BigInteger check = h.modPow(BundleOwnershipPSI.SUBGROUP_ORDER, BundleOwnershipPSI.MODP_PRIME);
        assertEquals(BigInteger.ONE, check);
    }

    @Test
    void testHashToGroupDeterministic() {
        BigInteger h1 = BundleOwnershipPSI.hashToGroup("sameBundleId");
        BigInteger h2 = BundleOwnershipPSI.hashToGroup("sameBundleId");
        assertEquals(h1, h2);
    }

    @Test
    void testHashToGroupDifferentInputs() {
        BigInteger h1 = BundleOwnershipPSI.hashToGroup("bundle1");
        BigInteger h2 = BundleOwnershipPSI.hashToGroup("bundle2");
        assertNotEquals(h1, h2);
    }

    @Test
    void testCommutativity() {
        String bundleId = "testBundle";
        BigInteger a = psi.generateSecret();
        BigInteger b = psi.generateSecret();
        BigInteger h = BundleOwnershipPSI.hashToGroup(bundleId);

        // H(x)^(ab) should equal H(x)^(ba)
        BigInteger hab = BundleOwnershipPSI.blind(BundleOwnershipPSI.blind(h, a), b);
        BigInteger hba = BundleOwnershipPSI.blind(BundleOwnershipPSI.blind(h, b), a);
        assertEquals(hab, hba);
    }

    @Test
    void testGenerateSecretInRange() {
        BigInteger secret = psi.generateSecret();
        assertTrue(secret.compareTo(BigInteger.TWO) >= 0);
        assertTrue(secret.compareTo(BundleOwnershipPSI.SUBGROUP_ORDER) < 0);
    }

    @Test
    void testFullProtocolWithMatch() {
        List<String> clientIds = Arrays.asList("bundleA", "bundleB", "bundleC");
        List<String> transportFiles = Arrays.asList("bundleX", "bundleB", "bundleY");

        BigInteger a = psi.generateSecret();
        BigInteger b = psi.generateSecret();

        List<BigInteger> clientBlinded = psi.clientBlindBundleIds(clientIds, a);
        var transportResponse = psi.transportProcess(clientBlinded, transportFiles, b);
        var matches = psi.clientFindMatches(
                transportResponse.doublyBlindedClientValues(),
                transportResponse.transportBlindedValues(), a);

        assertEquals(1, matches.size());
        assertEquals(1, matches.get(0).transportIndex()); // "bundleB" at index 1 in transport
        assertEquals(1, matches.get(0).clientIndex());     // "bundleB" at index 1 in client
    }

    @Test
    void testFullProtocolNoMatch() {
        List<String> clientIds = Arrays.asList("bundleA", "bundleB");
        List<String> transportFiles = Arrays.asList("bundleX", "bundleY");

        BigInteger a = psi.generateSecret();
        BigInteger b = psi.generateSecret();

        List<BigInteger> clientBlinded = psi.clientBlindBundleIds(clientIds, a);
        var transportResponse = psi.transportProcess(clientBlinded, transportFiles, b);
        var matches = psi.clientFindMatches(
                transportResponse.doublyBlindedClientValues(),
                transportResponse.transportBlindedValues(), a);

        assertTrue(matches.isEmpty());
    }

    @Test
    void testFullProtocolMultipleMatches() {
        List<String> clientIds = Arrays.asList("A", "B", "C", "D");
        List<String> transportFiles = Arrays.asList("X", "B", "D", "Z");

        BigInteger a = psi.generateSecret();
        BigInteger b = psi.generateSecret();

        List<BigInteger> clientBlinded = psi.clientBlindBundleIds(clientIds, a);
        var transportResponse = psi.transportProcess(clientBlinded, transportFiles, b);
        var matches = psi.clientFindMatches(
                transportResponse.doublyBlindedClientValues(),
                transportResponse.transportBlindedValues(), a);

        assertEquals(2, matches.size());
    }

    @Test
    void testEmptyClientWindow() {
        List<String> clientIds = List.of();
        List<String> transportFiles = Arrays.asList("bundleX", "bundleY");

        BigInteger a = psi.generateSecret();
        BigInteger b = psi.generateSecret();

        List<BigInteger> clientBlinded = psi.clientBlindBundleIds(clientIds, a);
        var transportResponse = psi.transportProcess(clientBlinded, transportFiles, b);
        var matches = psi.clientFindMatches(
                transportResponse.doublyBlindedClientValues(),
                transportResponse.transportBlindedValues(), a);

        assertTrue(matches.isEmpty());
    }

    @Test
    void testEmptyTransportBundles() {
        List<String> clientIds = Arrays.asList("bundleA", "bundleB");
        List<String> transportFiles = List.of();

        BigInteger a = psi.generateSecret();
        BigInteger b = psi.generateSecret();

        List<BigInteger> clientBlinded = psi.clientBlindBundleIds(clientIds, a);
        var transportResponse = psi.transportProcess(clientBlinded, transportFiles, b);
        var matches = psi.clientFindMatches(
                transportResponse.doublyBlindedClientValues(),
                transportResponse.transportBlindedValues(), a);

        assertTrue(matches.isEmpty());
    }
}
