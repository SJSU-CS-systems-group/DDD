# Bundle Layout

A Bundle is a ZipFile with an embedded encrypted ZipFile, called the payload.
The outer ZipFile has enough information to decrypt the payload without revealing the client's identity.

## Outer ZipFile

* payloads/payload (`PAYLOAD_DIR/PAYLOAD_FILENAME`): the payload encrypted using the double ratchet key between the client and server.
* bundle.id (`BUNDLEID_FILENAME`): the encrypted bundle ID.
* clientIdentity.pub (`CLIENT_IDENTITY_KEY`): the clientIdentityKey encrypted with an ephemeral DH key and the server's identity key.
  The file is a PEM file with the DH key first and then the encrypted clientIdentity key.
* clientBase.pub (`CLIENT_BASE_KEY`): the client base public key
* server_identity.pub (`SERVER_IDENTITY_KEY`): the server's public key. (Sanity check to make sure the bundle is intended for the correct server.)

## Payload ZipFile

* acknowledgement.txt (`PAYLOAD_ACK_NAME`): the last encrypted bundleId received
* routing.metadata (`PAYLOAD_ROUTING_NAME`): JSON file containing a map of transportIds and the number of times the client has seen the transport.
* crash_report.txt (`PAYLOAD_CRASH_NAME`): (Optional) any crash report that was generated recently
* ADU (`PAYLOAD_ADU_NAME`): the directory containing ADUs in the form `APPID/ADU_NUMBER`
