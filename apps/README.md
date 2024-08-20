
# APPS

## Project Description
Contains various additional apps such as CLI, Echo Adapter, K9 Server, etc.

## COMPILATION
Compile app package with ``` mvn clean install ```

**Echo Application**
Adapter for bundle server
*Note: Modify root directory in application properties*
```
java jar apps\echo\server\target\echo-0.0.1-SNAPSHOT.jar <BundleServerURL>
```

**K9 Application**
Adapter for K9 Server
```
java -jar apps/k9/server/target/k9-0.0.1-SNAPSHOT.jar <BundleServerURL>
```

**CLI Tool**
Encrypt bundle
```
java -jar apps\cli\target\cli-1.0-SNAPSHOT-jar-with-dependencies.jar encrypt-bundle --applicationYaml <application resources file> --appProps <application.properties file> --decrypted-bundle <bundle.decrypted> --clientId <client ID>
```

Decrypt bundle
```
java -jar apps\cli\target\cli-1.0-SNAPSHOT-jar-with-dependencies.jar decrypt-bundle --applicationYaml <application resources file> --appProps <application.properties file> --bundle <bundle path>     
```
