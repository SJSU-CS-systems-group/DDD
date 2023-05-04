// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: FileService.proto

package com.ddd.bundletransport.service;

/**
 * Protobuf type {@code protobuf.Bytes}
 */
public  final class Bytes extends
    com.google.protobuf.GeneratedMessageLite<
        Bytes, Bytes.Builder> implements
    // @@protoc_insertion_point(message_implements:protobuf.Bytes)
    BytesOrBuilder {
  private Bytes() {
    value_ = com.google.protobuf.ByteString.EMPTY;
    transportId_ = "";
  }
  public static final int VALUE_FIELD_NUMBER = 1;
  private com.google.protobuf.ByteString value_;
  /**
   * <code>bytes value = 1;</code>
   * @return The value.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString getValue() {
    return value_;
  }
  /**
   * <code>bytes value = 1;</code>
   * @param value The value to set.
   */
  private void setValue(com.google.protobuf.ByteString value) {
    java.lang.Class<?> valueClass = value.getClass();
  
    value_ = value;
  }
  /**
   * <code>bytes value = 1;</code>
   */
  private void clearValue() {
    
    value_ = getDefaultInstance().getValue();
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

  public static com.ddd.bundletransport.service.Bytes parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data);
  }
  public static com.ddd.bundletransport.service.Bytes parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.Bytes parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data);
  }
  public static com.ddd.bundletransport.service.Bytes parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.Bytes parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data);
  }
  public static com.ddd.bundletransport.service.Bytes parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.Bytes parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input);
  }
  public static com.ddd.bundletransport.service.Bytes parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.Bytes parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return parseDelimitedFrom(DEFAULT_INSTANCE, input);
  }
  public static com.ddd.bundletransport.service.Bytes parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return parseDelimitedFrom(DEFAULT_INSTANCE, input, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.Bytes parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input);
  }
  public static com.ddd.bundletransport.service.Bytes parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input, extensionRegistry);
  }

  public static Builder newBuilder() {
    return (Builder) DEFAULT_INSTANCE.createBuilder();
  }
  public static Builder newBuilder(com.ddd.bundletransport.service.Bytes prototype) {
    return (Builder) DEFAULT_INSTANCE.createBuilder(prototype);
  }

  /**
   * Protobuf type {@code protobuf.Bytes}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageLite.Builder<
        com.ddd.bundletransport.service.Bytes, Builder> implements
      // @@protoc_insertion_point(builder_implements:protobuf.Bytes)
      com.ddd.bundletransport.service.BytesOrBuilder {
    // Construct using com.ddd.bundletransport.service.Bytes.newBuilder()
    private Builder() {
      super(DEFAULT_INSTANCE);
    }


    /**
     * <code>bytes value = 1;</code>
     * @return The value.
     */
    @java.lang.Override
    public com.google.protobuf.ByteString getValue() {
      return instance.getValue();
    }
    /**
     * <code>bytes value = 1;</code>
     * @param value The value to set.
     * @return This builder for chaining.
     */
    public Builder setValue(com.google.protobuf.ByteString value) {
      copyOnWrite();
      instance.setValue(value);
      return this;
    }
    /**
     * <code>bytes value = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearValue() {
      copyOnWrite();
      instance.clearValue();
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

    // @@protoc_insertion_point(builder_scope:protobuf.Bytes)
  }
  @java.lang.Override
  @java.lang.SuppressWarnings({"unchecked", "fallthrough"})
  protected final java.lang.Object dynamicMethod(
      com.google.protobuf.GeneratedMessageLite.MethodToInvoke method,
      java.lang.Object arg0, java.lang.Object arg1) {
    switch (method) {
      case NEW_MUTABLE_INSTANCE: {
        return new com.ddd.bundletransport.service.Bytes();
      }
      case NEW_BUILDER: {
        return new Builder();
      }
      case BUILD_MESSAGE_INFO: {
          java.lang.Object[] objects = new java.lang.Object[] {
            "value_",
            "transportId_",
          };
          java.lang.String info =
              "\u0000\u0002\u0000\u0000\u0001\u0002\u0002\u0000\u0000\u0000\u0001\n\u0002\u0208" +
              "";
          return newMessageInfo(DEFAULT_INSTANCE, info, objects);
      }
      // fall through
      case GET_DEFAULT_INSTANCE: {
        return DEFAULT_INSTANCE;
      }
      case GET_PARSER: {
        com.google.protobuf.Parser<com.ddd.bundletransport.service.Bytes> parser = PARSER;
        if (parser == null) {
          synchronized (com.ddd.bundletransport.service.Bytes.class) {
            parser = PARSER;
            if (parser == null) {
              parser =
                  new DefaultInstanceBasedParser<com.ddd.bundletransport.service.Bytes>(
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


  // @@protoc_insertion_point(class_scope:protobuf.Bytes)
  private static final com.ddd.bundletransport.service.Bytes DEFAULT_INSTANCE;
  static {
    Bytes defaultInstance = new Bytes();
    // New instances are implicitly immutable so no need to make
    // immutable.
    DEFAULT_INSTANCE = defaultInstance;
    com.google.protobuf.GeneratedMessageLite.registerDefaultInstance(
      Bytes.class, defaultInstance);
  }

  public static com.ddd.bundletransport.service.Bytes getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static volatile com.google.protobuf.Parser<Bytes> PARSER;

  public static com.google.protobuf.Parser<Bytes> parser() {
    return DEFAULT_INSTANCE.getParserForType();
  }
}

