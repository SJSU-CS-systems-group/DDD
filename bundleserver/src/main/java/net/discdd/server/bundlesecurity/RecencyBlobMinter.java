package net.discdd.server.bundlesecurity;

import com.google.protobuf.ByteString;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;
import org.whispersystems.libsignal.InvalidKeyException;

import java.security.SecureRandom;

/**
 * Mints server-signed recency blobs. This is the only place the server creates one: the gRPC {@code getRecencyBlob}
 * RPC ({@code ServerBundleTransmission#getRecencyBlob}, for a TLS peer) and the dropbox recency handler
 * ({@code DropboxRecencyHandler}, for a dropbox's TLS key carried in a control ADU) both call {@link #mint}, so a
 * dropbox's blob is byte-for-byte the kind a transport gets.
 * <p>
 * A blob is {@code {version 0, random nonce, now, senderId}}, signed with the server identity (signing) key; the
 * response carries status SUCCESS, the signature and the server identity public key, which is what
 * {@code ClientBundleTransmission.processRecencyBlob} checks. It takes the {@link ServerSecurity} as an argument
 * because in-process handlers reach it through an {@code ObjectProvider} (see {@code InternalAppHandler}).
 */
public final class RecencyBlobMinter {
    private static final SecureRandom secureRandom = new SecureRandom();

    private RecencyBlobMinter() {}

    /**
     * @param serverSecurity the server's keys
     * @param senderId       the sender ID the blob names: {@code SecurityUtils.generateID} of the holder's TLS public
     *                       key (X.509 DER)
     * @return a signed SUCCESS response
     * @throws InvalidKeyException if the signing key cannot sign
     */
    public static GetRecencyBlobResponse mint(ServerSecurity serverSecurity, String senderId) throws
            InvalidKeyException {
        var blob = RecencyBlob.newBuilder()
                .setVersion(0)
                .setNonce(secureRandom.nextInt())
                .setBlobTimestamp(System.currentTimeMillis())
                .setSenderId(senderId)
                .build();
        byte[] signature = SecurityUtils.signMessageRaw(blob.toByteArray(), serverSecurity.getSigningKey());
        byte[] publicKeyBytes = serverSecurity.getIdentityPublicKey().serialize();
        return GetRecencyBlobResponse.newBuilder()
                .setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS)
                .setRecencyBlob(blob)
                .setRecencyBlobSignature(ByteString.copyFrom(signature))
                .setServerPublicKey(ByteString.copyFrom(publicKeyBytes))
                .build();
    }
}
