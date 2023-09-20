// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: FileService.proto

package com.ddd.bundletransport.service;

/**
 * Protobuf type {@code protobuf.FileUploadResponse}
 */
public  final class FileUploadResponse extends
    com.google.protobuf.GeneratedMessageLite<
        FileUploadResponse, FileUploadResponse.Builder> implements
    // @@protoc_insertion_point(message_implements:protobuf.FileUploadResponse)
    FileUploadResponseOrBuilder {
  private FileUploadResponse() {
    name_ = "";
  }
  public static final int NAME_FIELD_NUMBER = 1;
  private java.lang.String name_;
  /**
   * <code>string name = 1;</code>
   * @return The name.
   */
  @java.lang.Override
  public java.lang.String getName() {
    return name_;
  }
  /**
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getNameBytes() {
    return com.google.protobuf.ByteString.copyFromUtf8(name_);
  }
  /**
   * <code>string name = 1;</code>
   * @param value The name to set.
   */
  private void setName(
      java.lang.String value) {
    java.lang.Class<?> valueClass = value.getClass();
  
    name_ = value;
  }
  /**
   * <code>string name = 1;</code>
   */
  private void clearName() {
    
    name_ = getDefaultInstance().getName();
  }
  /**
   * <code>string name = 1;</code>
   * @param value The bytes for name to set.
   */
  private void setNameBytes(
      com.google.protobuf.ByteString value) {
    checkByteStringIsUtf8(value);
    name_ = value.toStringUtf8();
    
  }

  public static final int STATUS_FIELD_NUMBER = 2;
  private int status_;
  /**
   * <code>.protobuf.Status status = 2;</code>
   * @return The enum numeric value on the wire for status.
   */
  @java.lang.Override
  public int getStatusValue() {
    return status_;
  }
  /**
   * <code>.protobuf.Status status = 2;</code>
   * @return The status.
   */
  @java.lang.Override
  public com.ddd.bundletransport.service.Status getStatus() {
    com.ddd.bundletransport.service.Status result = com.ddd.bundletransport.service.Status.forNumber(status_);
    return result == null ? com.ddd.bundletransport.service.Status.UNRECOGNIZED : result;
  }
  /**
   * <code>.protobuf.Status status = 2;</code>
   * @param value The enum numeric value on the wire for status to set.
   */
  private void setStatusValue(int value) {
      status_ = value;
  }
  /**
   * <code>.protobuf.Status status = 2;</code>
   * @param value The status to set.
   */
  private void setStatus(com.ddd.bundletransport.service.Status value) {
    status_ = value.getNumber();
    
  }
  /**
   * <code>.protobuf.Status status = 2;</code>
   */
  private void clearStatus() {
    
    status_ = 0;
  }

  public static com.ddd.bundletransport.service.FileUploadResponse parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data);
  }
  public static com.ddd.bundletransport.service.FileUploadResponse parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.FileUploadResponse parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data);
  }
  public static com.ddd.bundletransport.service.FileUploadResponse parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.FileUploadResponse parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data);
  }
  public static com.ddd.bundletransport.service.FileUploadResponse parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.FileUploadResponse parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input);
  }
  public static com.ddd.bundletransport.service.FileUploadResponse parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.FileUploadResponse parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return parseDelimitedFrom(DEFAULT_INSTANCE, input);
  }
  public static com.ddd.bundletransport.service.FileUploadResponse parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return parseDelimitedFrom(DEFAULT_INSTANCE, input, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.FileUploadResponse parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input);
  }
  public static com.ddd.bundletransport.service.FileUploadResponse parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input, extensionRegistry);
  }

  public static Builder newBuilder() {
    return (Builder) DEFAULT_INSTANCE.createBuilder();
  }
  public static Builder newBuilder(com.ddd.bundletransport.service.FileUploadResponse prototype) {
    return (Builder) DEFAULT_INSTANCE.createBuilder(prototype);
  }

  /**
   * Protobuf type {@code protobuf.FileUploadResponse}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageLite.Builder<
        com.ddd.bundletransport.service.FileUploadResponse, Builder> implements
      // @@protoc_insertion_point(builder_implements:protobuf.FileUploadResponse)
      com.ddd.bundletransport.service.FileUploadResponseOrBuilder {
    // Construct using com.ddd.bundletransport.service.FileUploadResponse.newBuilder()
    private Builder() {
      super(DEFAULT_INSTANCE);
    }


    /**
     * <code>string name = 1;</code>
     * @return The name.
     */
    @java.lang.Override
    public java.lang.String getName() {
      return instance.getName();
    }
    /**
     * <code>string name = 1;</code>
     * @return The bytes for name.
     */
    @java.lang.Override
    public com.google.protobuf.ByteString
        getNameBytes() {
      return instance.getNameBytes();
    }
    /**
     * <code>string name = 1;</code>
     * @param value The name to set.
     * @return This builder for chaining.
     */
    public Builder setName(
        java.lang.String value) {
      copyOnWrite();
      instance.setName(value);
      return this;
    }
    /**
     * <code>string name = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearName() {
      copyOnWrite();
      instance.clearName();
      return this;
    }
    /**
     * <code>string name = 1;</code>
     * @param value The bytes for name to set.
     * @return This builder for chaining.
     */
    public Builder setNameBytes(
        com.google.protobuf.ByteString value) {
      copyOnWrite();
      instance.setNameBytes(value);
      return this;
    }

    /**
     * <code>.protobuf.Status status = 2;</code>
     * @return The enum numeric value on the wire for status.
     */
    @java.lang.Override
    public int getStatusValue() {
      return instance.getStatusValue();
    }
    /**
     * <code>.protobuf.Status status = 2;</code>
     * @param value The status to set.
     * @return This builder for chaining.
     */
    public Builder setStatusValue(int value) {
      copyOnWrite();
      instance.setStatusValue(value);
      return this;
    }
    /**
     * <code>.protobuf.Status status = 2;</code>
     * @return The status.
     */
    @java.lang.Override
    public com.ddd.bundletransport.service.Status getStatus() {
      return instance.getStatus();
    }
    /**
     * <code>.protobuf.Status status = 2;</code>
     * @param value The enum numeric value on the wire for status to set.
     * @return This builder for chaining.
     */
    public Builder setStatus(com.ddd.bundletransport.service.Status value) {
      copyOnWrite();
      instance.setStatus(value);
      return this;
    }
    /**
     * <code>.protobuf.Status status = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearStatus() {
      copyOnWrite();
      instance.clearStatus();
      return this;
    }

    // @@protoc_insertion_point(builder_scope:protobuf.FileUploadResponse)
  }
  @java.lang.Override
  @java.lang.SuppressWarnings({"unchecked", "fallthrough"})
  protected final java.lang.Object dynamicMethod(
      com.google.protobuf.GeneratedMessageLite.MethodToInvoke method,
      java.lang.Object arg0, java.lang.Object arg1) {
    switch (method) {
      case NEW_MUTABLE_INSTANCE: {
        return new com.ddd.bundletransport.service.FileUploadResponse();
      }
      case NEW_BUILDER: {
        return new Builder();
      }
      case BUILD_MESSAGE_INFO: {
          java.lang.Object[] objects = new java.lang.Object[] {
            "name_",
            "status_",
          };
          java.lang.String info =
              "\u0000\u0002\u0000\u0000\u0001\u0002\u0002\u0000\u0000\u0000\u0001\u0208\u0002\f" +
              "";
          return newMessageInfo(DEFAULT_INSTANCE, info, objects);
      }
      // fall through
      case GET_DEFAULT_INSTANCE: {
        return DEFAULT_INSTANCE;
      }
      case GET_PARSER: {
        com.google.protobuf.Parser<com.ddd.bundletransport.service.FileUploadResponse> parser = PARSER;
        if (parser == null) {
          synchronized (com.ddd.bundletransport.service.FileUploadResponse.class) {
            parser = PARSER;
            if (parser == null) {
              parser =
                  new DefaultInstanceBasedParser<com.ddd.bundletransport.service.FileUploadResponse>(
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


  // @@protoc_insertion_point(class_scope:protobuf.FileUploadResponse)
  private static final com.ddd.bundletransport.service.FileUploadResponse DEFAULT_INSTANCE;
  static {
    FileUploadResponse defaultInstance = new FileUploadResponse();
    // New instances are implicitly immutable so no need to make
    // immutable.
    DEFAULT_INSTANCE = defaultInstance;
    com.google.protobuf.GeneratedMessageLite.registerDefaultInstance(
      FileUploadResponse.class, defaultInstance);
  }

  public static com.ddd.bundletransport.service.FileUploadResponse getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static volatile com.google.protobuf.Parser<FileUploadResponse> PARSER;

  public static com.google.protobuf.Parser<FileUploadResponse> parser() {
    return DEFAULT_INSTANCE.getParserForType();
  }
}

