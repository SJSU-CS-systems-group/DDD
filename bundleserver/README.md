Bundle Server (Java Spring)
===========================

Project Description
-------------------
Bundle Server is a Java Spring application that processes, stores, and distributes the uploaded bundles. Bundle Server is the component of the DDD system that runs entirely within a connected environment.

INSTALLATION
------------

MySQL installation:

download mysql zip  
run "mysqld --initialize --console" (only for the first time)  
run "mysqld --console"  (in admin mode)

root@localhost: password

to connect to DB -
run "mysql -u root -p"

```
CREATE DATABASE dtn_server_db;

use dtn_server_db;

create table app_data_table (
    app_name varchar(100) not null,
    client_id varchar(100) not null,
    adu_id int unsigned not null,
    direction varchar(6)
);
create table registered_app_adapter_table (
    app_id varchar(100) not null,
    address varchar(200) not null
);

create table client_data_changed_table (
    client_id varchar(100) not null,
    has_new_data bool not null
);
```

COMPILATION
----------------
Compile app package with ``` mvn clean install ```

For running server (in server mode), add custom properties in an application.properties file, outside of your git repository
and add its path as program argument on run command.
``` java -jar {jar file} {custom application.properties file} ```

Sample application.properties file:
```
spring.datasource.password = tripti
spring.datasource.password = password
bundle-server.bundle-store-root = C:/Users/tript/IdeaProjects/DDD/bundleserver
```
Sample server run command:
```
java -jar bundleserver/target/bundleserver-0.0.1-SNAPSHOT.jar C:/Users/tripti/Downloads/application.properties   
```

NOTE
- Do not add your custom application.properties in git
- Use IntelliJ IDEA for IDE

