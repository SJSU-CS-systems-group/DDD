# Generated by the gRPC Python protocol compiler plugin. DO NOT EDIT!
"""Client and server classes corresponding to protobuf-defined services."""
import grpc

import DTNCommunication_pb2 as DTNCommunication__pb2


class DTNCommunicationStub(object):
    """Missing associated documentation comment in .proto file."""

    def __init__(self, channel):
        """Constructor.

        Args:
            channel: A grpc.Channel.
        """
        self.registerAdapter = channel.unary_unary(
                '/DTNCommunication/registerAdapter',
                request_serializer=DTNCommunication__pb2.ConnectionData.SerializeToString,
                response_deserializer=DTNCommunication__pb2.ResponseStatus.FromString,
                )


class DTNCommunicationServicer(object):
    """Missing associated documentation comment in .proto file."""

    def registerAdapter(self, request, context):
        """Missing associated documentation comment in .proto file."""
        context.set_code(grpc.StatusCode.UNIMPLEMENTED)
        context.set_details('Method not implemented!')
        raise NotImplementedError('Method not implemented!')


def add_DTNCommunicationServicer_to_server(servicer, server):
    rpc_method_handlers = {
            'registerAdapter': grpc.unary_unary_rpc_method_handler(
                    servicer.registerAdapter,
                    request_deserializer=DTNCommunication__pb2.ConnectionData.FromString,
                    response_serializer=DTNCommunication__pb2.ResponseStatus.SerializeToString,
            ),
    }
    generic_handler = grpc.method_handlers_generic_handler(
            'DTNCommunication', rpc_method_handlers)
    server.add_generic_rpc_handlers((generic_handler,))


 # This class is part of an EXPERIMENTAL API.
class DTNCommunication(object):
    """Missing associated documentation comment in .proto file."""

    @staticmethod
    def registerAdapter(request,
            target,
            options=(),
            channel_credentials=None,
            call_credentials=None,
            insecure=False,
            compression=None,
            wait_for_ready=None,
            timeout=None,
            metadata=None):
        return grpc.experimental.unary_unary(request, target, '/DTNCommunication/registerAdapter',
            DTNCommunication__pb2.ConnectionData.SerializeToString,
            DTNCommunication__pb2.ResponseStatus.FromString,
            options, channel_credentials,
            insecure, call_credentials, compression, wait_for_ready, timeout, metadata)
