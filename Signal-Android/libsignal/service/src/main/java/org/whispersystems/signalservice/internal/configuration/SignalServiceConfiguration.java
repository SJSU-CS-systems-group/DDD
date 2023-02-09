package org.whispersystems.signalservice.internal.configuration;



import java.util.List;
import java.util.Map;
import java.util.Optional;

import okhttp3.Dns;
import okhttp3.Interceptor;

public final class SignalServiceConfiguration {

  private final SignalServiceUrl[]           signalServiceUrls;
  private final Map<Integer, SignalCdnUrl[]> signalCdnUrlMap;
  private final SignalCdsiUrl[]              signalCdsiUrls;
  private final SignalKeyBackupServiceUrl[]  signalKeyBackupServiceUrls;
  private final SignalStorageUrl[]           signalStorageUrls;
  private final List<Interceptor>            networkInterceptors;
  private final Optional<Dns>                dns;
  private final Optional<SignalProxy>        proxy;
  private final byte[]                       zkGroupServerPublicParams;

  public SignalServiceConfiguration(SignalServiceUrl[] signalServiceUrls,
                                    Map<Integer, SignalCdnUrl[]> signalCdnUrlMap,
                                    SignalKeyBackupServiceUrl[] signalKeyBackupServiceUrls,
                                    SignalStorageUrl[] signalStorageUrls,
                                    SignalCdsiUrl[] signalCdsiUrls,
                                    List<Interceptor> networkInterceptors,
                                    Optional<Dns> dns,
                                    Optional<SignalProxy> proxy,
                                    byte[] zkGroupServerPublicParams)
  {
    this.signalServiceUrls          = signalServiceUrls;
    this.signalCdnUrlMap            = signalCdnUrlMap;
    this.signalCdsiUrls             = signalCdsiUrls;
    this.signalKeyBackupServiceUrls = signalKeyBackupServiceUrls;
    this.signalStorageUrls          = signalStorageUrls;
    this.networkInterceptors        = networkInterceptors;
    this.dns                        = dns;
    this.proxy                      = proxy;
    this.zkGroupServerPublicParams  = zkGroupServerPublicParams;
  }

  public SignalServiceUrl[] getSignalServiceUrls() {
    return signalServiceUrls;
  }

  public Map<Integer, SignalCdnUrl[]> getSignalCdnUrlMap() {
    return signalCdnUrlMap;
  }

  public SignalCdsiUrl[] getSignalCdsiUrls() {
    return signalCdsiUrls;
  }

  public SignalKeyBackupServiceUrl[] getSignalKeyBackupServiceUrls() {
    return signalKeyBackupServiceUrls;
  }

  public SignalStorageUrl[] getSignalStorageUrls() {
    return signalStorageUrls;
  }

  public List<Interceptor> getNetworkInterceptors() {
    return networkInterceptors;
  }

  public Optional<Dns> getDns() {
    return dns;
  }

  public byte[] getZkGroupServerPublicParams() {
    return zkGroupServerPublicParams;
  }

  public Optional<SignalProxy> getSignalProxy() {
    return proxy;
  }
}
