# -*- coding: utf-8 -*-
# Generated by the protocol buffer compiler.  DO NOT EDIT!
# source: DTNCommunication.proto
"""Generated protocol buffer code."""
from google.protobuf import descriptor as _descriptor
from google.protobuf import descriptor_pool as _descriptor_pool
from google.protobuf import symbol_database as _symbol_database
from google.protobuf.internal import builder as _builder
# @@protoc_insertion_point(imports)

_sym_db = _symbol_database.Default()




DESCRIPTOR = _descriptor_pool.Default().AddSerializedFile(b'\n\x16\x44TNCommunication.proto\".\n\x0e\x43onnectionData\x12\x0f\n\x07\x61ppName\x18\x01 \x01(\t\x12\x0b\n\x03url\x18\x02 \x01(\t\"/\n\x0eResponseStatus\x12\x0c\n\x04\x63ode\x18\x01 \x01(\x05\x12\x0f\n\x07message\x18\x02 \x01(\t2I\n\x10\x44TNCommunication\x12\x35\n\x0fregisterAdapter\x12\x0f.ConnectionData\x1a\x0f.ResponseStatus\"\x00\x42,\n(edu.sjsu.dtn.server.communicationserviceP\x01\x62\x06proto3')

_globals = globals()
_builder.BuildMessageAndEnumDescriptors(DESCRIPTOR, _globals)
_builder.BuildTopDescriptorsAndMessages(DESCRIPTOR, 'DTNCommunication_pb2', _globals)
if _descriptor._USE_C_DESCRIPTORS == False:
  DESCRIPTOR._options = None
  DESCRIPTOR._serialized_options = b'\n(edu.sjsu.dtn.server.communicationserviceP\001'
  _globals['_CONNECTIONDATA']._serialized_start=26
  _globals['_CONNECTIONDATA']._serialized_end=72
  _globals['_RESPONSESTATUS']._serialized_start=74
  _globals['_RESPONSESTATUS']._serialized_end=121
  _globals['_DTNCOMMUNICATION']._serialized_start=123
  _globals['_DTNCOMMUNICATION']._serialized_end=196
# @@protoc_insertion_point(module_scope)
