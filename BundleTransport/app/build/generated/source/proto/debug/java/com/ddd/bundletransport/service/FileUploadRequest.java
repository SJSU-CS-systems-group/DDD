// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: FileService.proto

package com.ddd.bundletransport.service;

/**
 * Protobuf type {@code protobuf.FileUploadRequest}
 */
public  final class FileUploadRequest extends
    com.google.protobuf.GeneratedMessageLite<
        FileUploadRequest, FileUploadRequest.Builder> implements
    // @@protoc_insertion_point(message_implements:protobuf.FileUploadRequest)
    FileUploadRequestOrBuilder {
  private FileUploadRequest() {
  }
  private int requestCase_ = 0;
  private java.lang.Object request_;
  public enum RequestCase {
    METADATA(1),
    FILE(2),
    REQUEST_NOT_SET(0);
    private final int value;
    private RequestCase(int value) {
      this.value = value;
    }
    /**
     * @deprecated Use {@link #forNumber(int)} instead.
     */
    @java.lang.Deprecated
    public static RequestCase valueOf(int value) {
      return forNumber(value);
    }

    public static RequestCase forNumber(int value) {
      switch (value) {
        case 1: return METADATA;
        case 2: return FILE;
        case 0: return REQUEST_NOT_SET;
        default: return null;
      }
    }
    public int getNumber() {
      return this.value;
    }
  };

  @java.lang.Override
  public RequestCase
  getRequestCase() {
    return RequestCase.forNumber(
        requestCase_);
  }

  private void clearRequest() {
    requestCase_ = 0;
    request_ = null;
  }

  public static final int METADATA_FIELD_NUMBER = 1;
  /**
   * <code>.protobuf.MetaData metadata = 1;</code>
   */
  @java.lang.Override
  public boolean hasMetadata() {
    return requestCase_ == 1;
  }
  /**
   * <code>.protobuf.MetaData metadata = 1;</code>
   */
  @java.lang.Override
  public com.ddd.bundletransport.service.MetaData getMetadata() {
    if (requestCase_ == 1) {
       return (com.ddd.bundletransport.service.MetaData) request_;
    }
    return com.ddd.bundletransport.service.MetaData.getDefaultInstance();
  }
  /**
   * <code>.protobuf.MetaData metadata = 1;</code>
   */
  private void setMetadata(com.ddd.bundletransport.service.MetaData value) {
    value.getClass();
  request_ = value;
    requestCase_ = 1;
  }
  /**
   * <code>.protobuf.MetaData metadata = 1;</code>
   */
  private void mergeMetadata(com.ddd.bundletransport.service.MetaData value) {
    value.getClass();
  if (requestCase_ == 1 &&
        request_ != com.ddd.bundletransport.service.MetaData.getDefaultInstance()) {
      request_ = com.ddd.bundletransport.service.MetaData.newBuilder((com.ddd.bundletransport.service.MetaData) request_)
          .mergeFrom(value).buildPartial();
    } else {
      request_ = value;
    }
    requestCase_ = 1;
  }
  /**
   * <code>.protobuf.MetaData metadata = 1;</code>
   */
  private void clearMetadata() {
    if (requestCase_ == 1) {
      requestCase_ = 0;
      request_ = null;
    }
  }

  public static final int FILE_FIELD_NUMBER = 2;
  /**
   * <code>.protobuf.File file = 2;</code>
   */
  @java.lang.Override
  public boolean hasFile() {
    return requestCase_ == 2;
  }
  /**
   * <code>.protobuf.File file = 2;</code>
   */
  @java.lang.Override
  public com.ddd.bundletransport.service.File getFile() {
    if (requestCase_ == 2) {
       return (com.ddd.bundletransport.service.File) request_;
    }
    return com.ddd.bundletransport.service.File.getDefaultInstance();
  }
  /**
   * <code>.protobuf.File file = 2;</code>
   */
  private void setFile(com.ddd.bundletransport.service.File value) {
    value.getClass();
  request_ = value;
    requestCase_ = 2;
  }
  /**
   * <code>.protobuf.File file = 2;</code>
   */
  private void mergeFile(com.ddd.bundletransport.service.File value) {
    value.getClass();
  if (requestCase_ == 2 &&
        request_ != com.ddd.bundletransport.service.File.getDefaultInstance()) {
      request_ = com.ddd.bundletransport.service.File.newBuilder((com.ddd.bundletransport.service.File) request_)
          .mergeFrom(value).buildPartial();
    } else {
      request_ = value;
    }
    requestCase_ = 2;
  }
  /**
   * <code>.protobuf.File file = 2;</code>
   */
  private void clearFile() {
    if (requestCase_ == 2) {
      requestCase_ = 0;
      request_ = null;
    }
  }

  public static com.ddd.bundletransport.service.FileUploadRequest parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data);
  }
  public static com.ddd.bundletransport.service.FileUploadRequest parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.FileUploadRequest parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data);
  }
  public static com.ddd.bundletransport.service.FileUploadRequest parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.FileUploadRequest parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data);
  }
  public static com.ddd.bundletransport.service.FileUploadRequest parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, data, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.FileUploadRequest parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input);
  }
  public static com.ddd.bundletransport.service.FileUploadRequest parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.FileUploadRequest parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return parseDelimitedFrom(DEFAULT_INSTANCE, input);
  }
  public static com.ddd.bundletransport.service.FileUploadRequest parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return parseDelimitedFrom(DEFAULT_INSTANCE, input, extensionRegistry);
  }
  public static com.ddd.bundletransport.service.FileUploadRequest parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input);
  }
  public static com.ddd.bundletransport.service.FileUploadRequest parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageLite.parseFrom(
        DEFAULT_INSTANCE, input, extensionRegistry);
  }

  public static Builder newBuilder() {
    return (Builder) DEFAULT_INSTANCE.createBuilder();
  }
  public static Builder newBuilder(com.ddd.bundletransport.service.FileUploadRequest prototype) {
    return (Builder) DEFAULT_INSTANCE.createBuilder(prototype);
  }

  /**
   * Protobuf type {@code protobuf.FileUploadRequest}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageLite.Builder<
        com.ddd.bundletransport.service.FileUploadRequest, Builder> implements
      // @@protoc_insertion_point(builder_implements:protobuf.FileUploadRequest)
      com.ddd.bundletransport.service.FileUploadRequestOrBuilder {
    // Construct using com.ddd.bundletransport.service.FileUploadRequest.newBuilder()
    private Builder() {
      super(DEFAULT_INSTANCE);
    }

    @java.lang.Override
    public RequestCase
        getRequestCase() {
      return instance.getRequestCase();
    }

    public Builder clearRequest() {
      copyOnWrite();
      instance.clearRequest();
      return this;
    }


    /**
     * <code>.protobuf.MetaData metadata = 1;</code>
     */
    @java.lang.Override
    public boolean hasMetadata() {
      return instance.hasMetadata();
    }
    /**
     * <code>.protobuf.MetaData metadata = 1;</code>
     */
    @java.lang.Override
    public com.ddd.bundletransport.service.MetaData getMetadata() {
      return instance.getMetadata();
    }
    /**
     * <code>.protobuf.MetaData metadata = 1;</code>
     */
    public Builder setMetadata(com.ddd.bundletransport.service.MetaData value) {
      copyOnWrite();
      instance.setMetadata(value);
      return this;
    }
    /**
     * <code>.protobuf.MetaData metadata = 1;</code>
     */
    public Builder setMetadata(
        com.ddd.bundletransport.service.MetaData.Builder builderForValue) {
      copyOnWrite();
      instance.setMetadata(builderForValue.build());
      return this;
    }
    /**
     * <code>.protobuf.MetaData metadata = 1;</code>
     */
    public Builder mergeMetadata(com.ddd.bundletransport.service.MetaData value) {
      copyOnWrite();
      instance.mergeMetadata(value);
      return this;
    }
    /**
     * <code>.protobuf.MetaData metadata = 1;</code>
     */
    public Builder clearMetadata() {
      copyOnWrite();
      instance.clearMetadata();
      return this;
    }

    /**
     * <code>.protobuf.File file = 2;</code>
     */
    @java.lang.Override
    public boolean hasFile() {
      return instance.hasFile();
    }
    /**
     * <code>.protobuf.File file = 2;</code>
     */
    @java.lang.Override
    public com.ddd.bundletransport.service.File getFile() {
      return instance.getFile();
    }
    /**
     * <code>.protobuf.File file = 2;</code>
     */
    public Builder setFile(com.ddd.bundletransport.service.File value) {
      copyOnWrite();
      instance.setFile(value);
      return this;
    }
    /**
     * <code>.protobuf.File file = 2;</code>
     */
    public Builder setFile(
        com.ddd.bundletransport.service.File.Builder builderForValue) {
      copyOnWrite();
      instance.setFile(builderForValue.build());
      return this;
    }
    /**
     * <code>.protobuf.File file = 2;</code>
     */
    public Builder mergeFile(com.ddd.bundletransport.service.File value) {
      copyOnWrite();
      instance.mergeFile(value);
      return this;
    }
    /**
     * <code>.protobuf.File file = 2;</code>
     */
    public Builder clearFile() {
      copyOnWrite();
      instance.clearFile();
      return this;
    }

    // @@protoc_insertion_point(builder_scope:protobuf.FileUploadRequest)
  }
  @java.lang.Override
  @java.lang.SuppressWarnings({"unchecked", "fallthrough"})
  protected final java.lang.Object dynamicMethod(
      com.google.protobuf.GeneratedMessageLite.MethodToInvoke method,
      java.lang.Object arg0, java.lang.Object arg1) {
    switch (method) {
      case NEW_MUTABLE_INSTANCE: {
        return new com.ddd.bundletransport.service.FileUploadRequest();
      }
      case NEW_BUILDER: {
        return new Builder();
      }
      case BUILD_MESSAGE_INFO: {
          java.lang.Object[] objects = new java.lang.Object[] {
            "request_",
            "requestCase_",
            com.ddd.bundletransport.service.MetaData.class,
            com.ddd.bundletransport.service.File.class,
          };
          java.lang.String info =
              "\u0000\u0002\u0001\u0000\u0001\u0002\u0002\u0000\u0000\u0000\u0001<\u0000\u0002<" +
              "\u0000";
          return newMessageInfo(DEFAULT_INSTANCE, info, objects);
      }
      // fall through
      case GET_DEFAULT_INSTANCE: {
        return DEFAULT_INSTANCE;
      }
      case GET_PARSER: {
        com.google.protobuf.Parser<com.ddd.bundletransport.service.FileUploadRequest> parser = PARSER;
        if (parser == null) {
          synchronized (com.ddd.bundletransport.service.FileUploadRequest.class) {
            parser = PARSER;
            if (parser == null) {
              parser =
                  new DefaultInstanceBasedParser<com.ddd.bundletransport.service.FileUploadRequest>(
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


  // @@protoc_insertion_point(class_scope:protobuf.FileUploadRequest)
  private static final com.ddd.bundletransport.service.FileUploadRequest DEFAULT_INSTANCE;
  static {
    FileUploadRequest defaultInstance = new FileUploadRequest();
    // New instances are implicitly immutable so no need to make
    // immutable.
    DEFAULT_INSTANCE = defaultInstance;
    com.google.protobuf.GeneratedMessageLite.registerDefaultInstance(
      FileUploadRequest.class, defaultInstance);
  }

  public static com.ddd.bundletransport.service.FileUploadRequest getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static volatile com.google.protobuf.Parser<FileUploadRequest> PARSER;

  public static com.google.protobuf.Parser<FileUploadRequest> parser() {
    return DEFAULT_INSTANCE.getParserForType();
  }
}

