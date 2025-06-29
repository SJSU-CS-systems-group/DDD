# DDD server setup

this document describes how to set up the DDD server environment for development and testing.

the bundle server and k9 server will both run on the same machine.
it's easy enough to run them on different machines,
but partitioning them to run multiple instances is beyond the scope of this document.

## pre-requisites

* DNS name provider. we will be using `canary.ravlykmail.com` as the example in this document.
* mysql server installed and running.
* a java 17+ JDK installed.

## dns setup

we need to setup the following DNS records:

* HOST: A canary.74.208.105.43
* MX: canary.ravlykmail.com MX 0 canary.ravlykmail.com
* SPF: TXT canary.ravlykmail.com TXT v=spf1 a:ravlykmail.com -all
* DKIM: TXT mail._domainkey.canary.ravlykmail.com TXT v=DKIM1; h=sha256; k=rsa; p=<base64 encoded public key>
* DMARC: TXT _dmarc.canary.ravlykmail.com TXT v=DMARC1; p=quarantine; aspf=s; adkim=r

## create a ddd user with database access

1. create the user `ddd` with a password of your choice.
    ```
    adduser --disabled-password --shell /bin/bash --gecos "discdd account" ddd
    ```

1. give the user `ddd` with password `your_password` (make it different from other passwords you use!) access to the mysql database.
    ```
    # mysql -u root -p
    MariaDB > CREATE USER 'ddd'@'localhost' IDENTIFIED BY 'your_password';
    MariaDB > GRANT ALL PRIVILEGES ON *.* TO 'ddd'@'localhost';
    MariaDB > FLUSH PRIVILEGES;
    ```
   
## build the code

### prerequisites

you need java 17+ and maven installed on your machine. you can install them using your package manager.

```bash
apt install openjdk-17-jre-headless maven
```

### do the rest as the ddd user

```bash
sudo su - ddd
```

### set up the maven settings.xml with your github credentials

create a `.m2` directory in your home directory if it doesn't exist ( `mkdir -p ~/.m2` ) , and add a `~/.m2/settings.xml` file with the following content:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                      http://maven.apache.org/xsd/settings-1.0.0.xsd">
<servers>
  <server>
    <id>github</id> <!-- Match ID -->
    <username>GITHUB_USERNAME</username>
    <password>GITHUB_TOKEN_WITH_PACKAGE_READ_ACCESS</password>
  </server>
</servers>
</settings>
```

you can use https://github.com/settings/tokens/new to generate a GITHUB_TOKEN _WITH_PACKAGE_READ_ACCESS.
if you make a classic token, you need to select the `read:packages` scope.

***NOTE***: if you get an error that looks like the below, you need to fix the token (either the value or the permissions it has) in your `settings.xml` file.
```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-dependency-plugin:2.8:get (default-cli) on project DDD: Couldn't download artifact: Missing:
```

### clone the repository and build the code

switch to the ddd user and clone the repository:

```bash
sudo su - ddd
mkdir git
cd git
git clone https://github.com/SJSU-CS-systems-group/DDD.git
cd DDD
mvn install
```

this will build both the BundleServer and K9 (email) server jar files.

### setup the systemd services

### create the systemd service files

in the ddd home directory directory, create a `systemd` directory with following service files:

systemd/bundleserver.service:
```ini
[Unit]
Description=DDD Bundle Server

[Service]
ExecStart=java -jar bundleserver.jar bundleserver.cfg
User=ddd
WorkingDirectory=/home/ddd/systemd
Restart=always
StandardOutput=append:/home/ddd/systemd/bundleserver.log
StandardError=append:/home/ddd/systemd/bundleserver.log

[Install]
WantedBy=multi-user.target
```

systemd/k9.service:
```ini
[Unit]
Description=K9 Mail Service Adapter

[Service]
# it appears that systemd doesn't handle - in environment variables
# well, so we need to set it with env
ExecStart=java -jar k9.jar k9.cfg
User=ddd
WorkingDirectory=/home/ddd/systemd
Restart=always
StandardOutput=append:/home/ddd/systemd/k9.log
StandardError=append:/home/ddd/systemd/k9.log
AmbientCapabilities=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
```

### create the configuration files

systemd/bundleserver.cfg:
```properties
bundle-server.bundle-store-root = /home/ddd/bundleserver-data
spring.datasource.username = ddd
spring.datasource.password = <your_password>
serviceadapter.datacheck.interval = 30s
```

systemd/k9.cfg:
```properties
spring.datasource.username=ddd
spring.datasource.password=<your_password>
adapter-server.root-dir=/home/ddd/k9-data
bundle-server.url=127.0.0.1:7778
smtp.localDomain=YOUR_MAIL_DOMAIN
smtp.relay.host=127.0.0.1
smtp.relay.port=2525
smtp.localPort=25
smtp.tls.cert=/etc/.lego/certificates/YOUR_MAIL_DOMAIN.crt
smtp.tls.private=/etc/.lego/certificates/YOUR_MAIL_DOMAIN.key
```