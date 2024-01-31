import grpc
import DTNCommunication_pb2
import DTNCommunication_pb2_grpc
import logging
from concurrent import futures

class DTNCommunicationServicer(DTNCommunication_pb2_grpc.DTNCommunicationServicer):
  def registerAdapter(self, request, context):
    print("Server received: " + request.appName)
    return DTNCommunication_pb2.ResponseStatus(message="Hello " + request.appName)
  
def serve():
  port ="50051"
  server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
  DTNCommunication_pb2_grpc.add_DTNCommunicationServicer_to_server(DTNCommunicationServicer(), server)
  server.add_insecure_port('[::]:' + port)
  server.start()
  print("Server started") 
  server.wait_for_termination()

if __name__ == "__main__":
  logging.basicConfig()
  serve()
