Bundle Server (Java Spring)
===========================

Project Description
-------------------
Bundle Server is a Java Spring application that processes, stores, and distributes the uploaded bundles. Bundle Server is the component of the DDD system that runs entirely within a connected environment.

INSTALLATION
------------

MSQL installation:

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
in the resources/application.yml file change the spring.datasource.password attribute to your mysql db password

Additional Notes
----------------

For running server (in server mode), add custom properties in a custom-application.properties file, at bundleserver/src/main/resources folder

Sample custom-application.properties file:
```
spring.datasource.password = tripti
bundle-server.bundle-store-root = C:/Users/tript/IdeaProjects/DDD/bundleserver
```

Use IntelliJ IDEA for IDE
