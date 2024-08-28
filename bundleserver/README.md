Bundle Server (Java Spring)
===========================

Project Description
-------------------
Bundle Server is a Java Spring application that processes, stores, and distributes the uploaded bundles. Bundle Server is the component of the DDD system that runs entirely within a connected environment.

COMPILATION
----------------
- Compile app package with ``` mvn clean install ```

- For running server (in server mode), add custom properties in an application.properties file, outside of your git repository and add its path as program argument on run command.
``` java -jar {jar file} {custom application.properties file} ```

Sample application.properties file:
```
spring.datasource.username = tripti
spring.datasource.password = password
bundle-server.bundle-store-root = C:/Users/tript/IdeaProjects/DDD/bundleserver
```
Sample server run command:
```
java -jar bundleserver/target/bundleserver-0.0.1-SNAPSHOT.jar C:/Users/tripti/Downloads/application.properties   
```
- Check for App ID list
```
<server run command> appids list 
```
- Register App for Adapter (if not yet registered or shown on list)
```
<server run command> appids update <App ID> <BundleServerURL>
```

NOTE
- Do not add your custom application.properties in git
- Use IntelliJ IDEA for IDE

