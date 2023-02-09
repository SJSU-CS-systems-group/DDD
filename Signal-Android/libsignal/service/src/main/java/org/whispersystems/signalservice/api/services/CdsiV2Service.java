package org.whispersystems.signalservice.api.services;

import com.google.protobuf.ByteString;

import org.signal.cdsi.proto.ClientRequest;
import org.signal.cdsi.proto.ClientResponse;
import org.signal.libsignal.protocol.util.ByteUtil;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.PNI;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.ServiceResponse;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.core.Single;

/**
 * Handles network interactions with CDSI, the SGX-backed version of the CDSv2 API.
 */
public final class CdsiV2Service {

  private static final String TAG = CdsiV2Service.class.getSimpleName();

  private static final UUID EMPTY_UUID         = new UUID(0, 0);
  private static final int  RESPONSE_ITEM_SIZE = 8 + 16 + 16; // 1 uint64 + 2 UUIDs

  private final CdsiSocket cdsiSocket;

  public CdsiV2Service(SignalServiceConfiguration configuration, String mrEnclave) {
    this.cdsiSocket = new CdsiSocket(configuration, mrEnclave);
  }

  public Single<ServiceResponse<Response>> getRegisteredUsers(String username, String password, Request request, Consumer<byte[]> tokenSaver) {
    return cdsiSocket
        .connect(username, password, buildClientRequest(request), tokenSaver)
        .map(CdsiV2Service::parseEntries)
        .collect(Collectors.toList())
        .flatMap(pages -> {
          Map<String, ResponseItem> all       = new HashMap<>();
          int                       quotaUsed = 0;

          for (Response page : pages) {
            all.putAll(page.getResults());
            quotaUsed += page.getQuotaUsedDebugOnly();
          }

          return Single.just(new Response(all, quotaUsed));
        })
        .map(result -> ServiceResponse.forResult(result, 200, null))
        .onErrorReturn(error -> {
          if (error instanceof NonSuccessfulResponseCodeException) {
            int status = ((NonSuccessfulResponseCodeException) error).getCode();
            return ServiceResponse.forApplicationError(error, status, null);
          } else {
            return ServiceResponse.forUnknownError(error);
          }
        });
  }

  private static Response parseEntries(ClientResponse clientResponse) {
    Map<String, ResponseItem> results = new HashMap<>();
    ByteBuffer                parser  = clientResponse.getE164PniAciTriples().asReadOnlyByteBuffer();

    while (parser.remaining() >= RESPONSE_ITEM_SIZE) {
      String e164    = "+" + parser.getLong();
      UUID   pniUuid = new UUID(parser.getLong(), parser.getLong());
      UUID   aciUuid = new UUID(parser.getLong(), parser.getLong());

      if (!pniUuid.equals(EMPTY_UUID)) {
        PNI pni = PNI.from(pniUuid);
        ACI aci = aciUuid.equals(EMPTY_UUID) ? null : ACI.from(aciUuid);
        results.put(e164, new ResponseItem(pni, Optional.ofNullable(aci)));
      }
    }

    return new Response(results, clientResponse.getDebugPermitsUsed());
  }

  private static ClientRequest buildClientRequest(Request request) {
    List<Long> previousE164s = parseAndSortE164Strings(request.previousE164s);
    List<Long> newE164s      = parseAndSortE164Strings(request.newE164s);
    List<Long> removedE164s  = parseAndSortE164Strings(request.removedE164s);

    ClientRequest.Builder builder = ClientRequest.newBuilder()
                                                 .setPrevE164S(toByteString(previousE164s))
                                                 .setNewE164S(toByteString(newE164s))
                                                 .setDiscardE164S(toByteString(removedE164s))
                                                 .setAciUakPairs(toByteString(request.serviceIds))
                                                 .setReturnAcisWithoutUaks(request.requireAcis);

    if (request.token != null) {
      builder.setToken(ByteString.copyFrom(request.token));
    }

    return builder.build();
  }

  private static ByteString toByteString(List<Long> numbers) {
    ByteString.Output os = ByteString.newOutput();

    for (long number : numbers) {
      try {
        os.write(ByteUtil.longToByteArray(number));
      } catch (IOException e) {
        throw new AssertionError("Failed to write long to ByteString", e);
      }
    }

    return os.toByteString();
  }

  private static ByteString toByteString(Map<ServiceId, ProfileKey> serviceIds) {
    ByteString.Output os = ByteString.newOutput();

    for (Map.Entry<ServiceId, ProfileKey> entry : serviceIds.entrySet()) {
      try {
        os.write(UuidUtil.toByteArray(entry.getKey().uuid()));
        os.write(UnidentifiedAccess.deriveAccessKeyFrom(entry.getValue()));
      } catch (IOException e) {
        throw new AssertionError("Failed to write long to ByteString", e);
      }
    }

    return os.toByteString();
  }

  private static List<Long> parseAndSortE164Strings(Collection<String> e164s) {
    return e164s.stream()
                .map(Long::parseLong)
                .sorted()
                .collect(Collectors.toList());

  }

  public static final class Request {
    final Set<String> previousE164s;
    final Set<String> newE164s;
    final Set<String> removedE164s;

    final Map<ServiceId, ProfileKey> serviceIds;

    final boolean requireAcis;

    final byte[] token;

    public Request(Set<String> previousE164s, Set<String> newE164s, Map<ServiceId, ProfileKey> serviceIds, boolean requireAcis, Optional<byte[]> token) {
      if (previousE164s.size() > 0 && !token.isPresent()) {
        throw new IllegalArgumentException("You must have a token if you have previousE164s!");
      }

      this.previousE164s = previousE164s;
      this.newE164s      = newE164s;
      this.removedE164s  = Collections.emptySet();
      this.serviceIds    = serviceIds;
      this.requireAcis   = requireAcis;
      this.token         = token.orElse(null);
    }

    public int serviceIdSize() {
      return previousE164s.size() + newE164s.size() + removedE164s.size() + serviceIds.size();
    }
  }

  public static final class Response {
    private final Map<String, ResponseItem> results;
    private final int                       quotaUsed;

    public Response(Map<String, ResponseItem> results, int quoteUsed) {
      this.results   = results;
      this.quotaUsed = quoteUsed;
    }

    public Map<String, ResponseItem> getResults() {
      return results;
    }

    /**
     * Tells you how much quota you used in the request. This should only be used for debugging/logging purposed, and should never be relied upon for making
     * actual decisions.
     */
    public int getQuotaUsedDebugOnly() {
      return quotaUsed;
    }
  }

  public static final class ResponseItem {
    private final PNI           pni;
    private final Optional<ACI> aci;

    public ResponseItem(PNI pni, Optional<ACI> aci) {
      this.pni = pni;
      this.aci = aci;
    }

    public PNI getPni() {
      return pni;
    }

    public Optional<ACI> getAci() {
      return aci;
    }

    public boolean hasAci() {
      return aci.isPresent();
    }
  }
}
