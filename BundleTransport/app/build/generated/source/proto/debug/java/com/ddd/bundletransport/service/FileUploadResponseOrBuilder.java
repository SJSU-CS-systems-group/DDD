// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: FileService.proto

package com.ddd.bundletransport.service;

public interface FileUploadResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:protobuf.FileUploadResponse)
    com.google.protobuf.MessageLiteOrBuilder {

  /**
   * <code>string name = 1;</code>
   * @return The name.
   */
  java.lang.String getName();
  /**
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  com.google.protobuf.ByteString
      getNameBytes();

  /**
   * <code>.protobuf.Status status = 2;</code>
   * @return The enum numeric value on the wire for status.
   */
  int getStatusValue();
  /**
   * <code>.protobuf.Status status = 2;</code>
   * @return The status.
   */
  com.ddd.bundletransport.service.Status getStatus();
}
