// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: BundleService.proto

package com.ddd.bundletransport.service;

/**
 * Protobuf type {@code protobuf.BundleDownloadRequest}
 */
public  final class BundleDownloadRequest extends
    com.google.protobuf.GeneratedMessageLite<
        BundleDownloadRequest, BundleDownloadRequest.Builder> implements
    // @@protoc_insertion_point(message_implements:protobuf.BundleDownloadRequest)
    BundleDownloadRequestOrBuilder {
  private BundleDownloadRequest() {
    bundleList_ = "";
    transportId_ = "";
  }
  public static final int BUNDLELIST_FIELD_NUMBER = 1;
  private java.lang.String bundleList_;
  /**
   * <code>string bundleList = 1;</code>
   * @return The bundleList.
   */
  @java.lang.Override
  public java.lang.String getBundleList() {
    return bundleList_;
  }
  /**
   * <code>string bundleList = 1;</code>
   * @return The bytes for bundleList.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getBundleListBytes() {
    return com.google.protobuf.ByteString.copyFromUtf8(bundleList_);
  }
  /**
   * <code>string bundleList = 1;</code>
   * @param value The bundleList to set.
   */
  private void setBundleList(
      java.lang.String value) {
    java.lang.Class<?> valueClass = value.getClass();
  
    bundleList_ = value;
  }
  /**
   * <code>string bundleList = 1;</code>
   */
  private void clearBundleList() {
    
    bundleList_ = getDefaultInstance().getBundleList();
  }
  /**
   * <code>string bundleList = 1;</code>
   * @param value The bytes for bundleList to set.
   */
  private void setBundleListBytes(
      com.google.protobuf.ByteString value) {
    checkByteStringIsUtf8(value);
    bundleList_ = value.toStringUtf8();
    
  }

  public static final int TRANSPORTID_FIELD_NUMBER = 2;
  private java.lang.String transportId_;
  /**
   * <code>string transportId = 2;</code>
   * @return The transportId.
   */
  @java.lang.Override
  public java.lang.String getTransportId() {
    return transportId_;
  }
  /**
   * <code>string transportId = 2;</code>
   * @return The bytes for transportId.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getTransportIdBytes() {
    return com.google.protobuf.ByteString.copyFromUtf8(transportId_);
  }
  /**
   * <code>string transportId = 2;</code>
   * @param value The transportId to set.
   */
  private void setTransportId(
      java.lang.String value) {
    java.lang.Class<?> valueClass = value.getClass();
  
    transportId_ = value;
  }
  /**
   * <code>string transportId = 2;</code>
   */
  private void clearTransportId() {
    
    transportId_ = getDefaultInstance().getTransportId();
  }
  /**
   * <code>string transportId = 2;</code>
   * @param value The bytes for transportId to set.
   */
  private void setTransportIdBytes(
      com.google.protobuf.ByteString value) {
    checkByteStringIsUtf8(value);
    transportId_ = value.toStringUtf8();
    
  }

  public static com.ddd.bundletransport.service.BundleDownloadRequest parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data);
  }
  public static com.ddd.bundletransport.service.BundleDownloadRequest parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.BundleDownloadRequest parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data);
  }
  public static com.ddd.bundletransport.service.BundleDownloadRequest parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.BundleDownloadRequest parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data);
  }
  public static com.ddd.bundletransport.service.BundleDownloadRequest parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.BundleDownloadRequest parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input);
  }
  public static com.ddd.bundletransport.service.BundleDownloadRequest parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.BundleDownloadRequest parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return parseDelimitedFrom(DEFAULT_INSTANCE, input);
  }
  public static com.ddd.bundletransport.service.BundleDownloadRequest parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return parseDelimitedFrom(DEFAULT_INSTANCE, input, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.BundleDownloadRequest parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input);
  }
  public static com.ddd.bundletransport.service.BundleDownloadRequest parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input, extensionRegistry);
  }

  public static Builder newBuilder() {
    return (Builder) DEFAULT_INSTANCE.createBuilder();
  }
  public static Builder newBuilder(com.ddd.bundletransport.service.BundleDownloadRequest prototype) {
    return (Builder) DEFAULT_INSTANCE.createBuilder(prototype);
  }

  /**
   * Protobuf type {@code protobuf.BundleDownloadRequest}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageLite.Builder<
        com.ddd.bundletransport.service.BundleDownloadRequest, Builder> implements
      // @@protoc_insertion_point(builder_implements:protobuf.BundleDownloadRequest)
      com.ddd.bundletransport.service.BundleDownloadRequestOrBuilder {
    // Construct using com.ddd.bundletransport.service.BundleDownloadRequest.newBuilder()
    private Builder() {
      super(DEFAULT_INSTANCE);
    }


    /**
     * <code>string bundleList = 1;</code>
     * @return The bundleList.
     */
    @java.lang.Override
    public java.lang.String getBundleList() {
      return instance.getBundleList();
    }
    /**
     * <code>string bundleList = 1;</code>
     * @return The bytes for bundleList.
     */
    @java.lang.Override
    public com.google.protobuf.ByteString
        getBundleListBytes() {
      return instance.getBundleListBytes();
    }
    /**
     * <code>string bundleList = 1;</code>
     * @param value The bundleList to set.
     * @return This builder for chaining.
     */
    public Builder setBundleList(
        java.lang.String value) {
      copyOnWrite();
      instance.setBundleList(value);
      return this;
    }
    /**
     * <code>string bundleList = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearBundleList() {
      copyOnWrite();
      instance.clearBundleList();
      return this;
    }
    /**
     * <code>string bundleList = 1;</code>
     * @param value The bytes for bundleList to set.
     * @return This builder for chaining.
     */
    public Builder setBundleListBytes(
        com.google.protobuf.ByteString value) {
      copyOnWrite();
      instance.setBundleListBytes(value);
      return this;
    }

    /**
     * <code>string transportId = 2;</code>
     * @return The transportId.
     */
    @java.lang.Override
    public java.lang.String getTransportId() {
      return instance.getTransportId();
    }
    /**
     * <code>string transportId = 2;</code>
     * @return The bytes for transportId.
     */
    @java.lang.Override
    public com.google.protobuf.ByteString
        getTransportIdBytes() {
      return instance.getTransportIdBytes();
    }
    /**
     * <code>string transportId = 2;</code>
     * @param value The transportId to set.
     * @return This builder for chaining.
     */
    public Builder setTransportId(
        java.lang.String value) {
      copyOnWrite();
      instance.setTransportId(value);
      return this;
    }
    /**
     * <code>string transportId = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearTransportId() {
      copyOnWrite();
      instance.clearTransportId();
      return this;
    }
    /**
     * <code>string transportId = 2;</code>
     * @param value The bytes for transportId to set.
     * @return This builder for chaining.
     */
    public Builder setTransportIdBytes(
        com.google.protobuf.ByteString value) {
      copyOnWrite();
      instance.setTransportIdBytes(value);
      return this;
    }

    // @@protoc_insertion_point(builder_scope:protobuf.BundleDownloadRequest)
  }
  @java.lang.Override
  @java.lang.SuppressWarnings({"unchecked", "fallthrough"})
  protected final java.lang.Object dynamicMethod(
      com.google.protobuf.GeneratedMessageLite.MethodToInvoke method,
      java.lang.Object arg0, java.lang.Object arg1) {
    switch (method) {
      case NEW_MUTABLE_INSTANCE: {
        return new com.ddd.bundletransport.service.BundleDownloadRequest();
      }
      case NEW_BUILDER: {
        return new Builder();
      }
      case BUILD_MESSAGE_INFO: {
          java.lang.Object[] objects = new java.lang.Object[] {
            "bundleList_",
            "transportId_",
          };
          java.lang.String info =
              "\u0000\u0002\u0000\u0000\u0001\u0002\u0002\u0000\u0000\u0000\u0001\u0208\u0002\u0208" +
              "";
          return newMessageInfo(DEFAULT_INSTANCE, info, objects);
      }
      // fall through
      case GET_DEFAULT_INSTANCE: {
        return DEFAULT_INSTANCE;
      }
      case GET_PARSER: {
        com.google.protobuf.Parser<com.ddd.bundletransport.service.BundleDownloadRequest> parser = PARSER;
        if (parser == null) {
          synchronized (com.ddd.bundletransport.service.BundleDownloadRequest.class) {
            parser = PARSER;
            if (parser == null) {
              parser =
                  new DefaultInstanceBasedParser<com.ddd.bundletransport.service.BundleDownloadRequest>(
                      DEFAULT_INSTANCE);
              PARSER = parser;
            }
          }
        }
        return parser;
    }
    case GET_MEMOIZED_IS_INITIALIZED: {
      return (byte) 1;
    }
    case SET_MEMOIZED_IS_INITIALIZED: {
      return null;
    }
    }
    throw new UnsupportedOperationException();
  }


  // @@protoc_insertion_point(class_scope:protobuf.BundleDownloadRequest)
  private static final com.ddd.bundletransport.service.BundleDownloadRequest DEFAULT_INSTANCE;
  static {
    BundleDownloadRequest defaultInstance = new BundleDownloadRequest();
    // New instances are implicitly immutable so no need to make
    // immutable.
    DEFAULT_INSTANCE = defaultInstance;
    com.google.protobuf.GeneratedMessageLite.registerDefaultInstance(
      BundleDownloadRequest.class, defaultInstance);
  }

  public static com.ddd.bundletransport.service.BundleDownloadRequest getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static volatile com.google.protobuf.Parser<BundleDownloadRequest> PARSER;

  public static com.google.protobuf.Parser<BundleDownloadRequest> parser() {
    return DEFAULT_INSTANCE.getParserForType();
  }
}

