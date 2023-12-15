import grpc
import logging
import click
import DTNCommunication_pb2
import DTNCommunication_pb2_grpc

@click.command()
@click.option('--url', prompt='URL of the DTN adapter to connect to')

def testConnection(url):
  print("Test client started")

  with grpc.insecure_channel(url) as channel:
    print(channel)

    stub = DTNCommunication_pb2_grpc.DTNCommunicationStub(channel)

    connection_data = DTNCommunication_pb2.ConnectionData(
      appName="test",
      url=url
    )
  
    try:
      response = stub.registerAdapter(connection_data)
      print("Test client received:" + response.message)
    except grpc.RpcError as e:
      print(f"Error during RPC call: {e}")

if __name__ == '__main__':
  logging.basicConfig()
  testConnection()