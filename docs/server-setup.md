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
* SPF: TXT canary.ravlykmail.com TXT v=spf1 a:canary.ravlykmail.com -all
* DKIM: TXT mail._domainkey.canary.ravlykmail.com TXT v=DKIM1; h=sha256; k=rsa; p=<base64 encoded public key>
* DMARC: TXT _dmarc.canary.ravlykmail.com TXT v=DMARC1; p=quarantine; aspf=s; adkim=r

## set up SSL certificates

we need an SSL certificate for `canary.ravlykmail.com`.
if you know how to create an SSL certificate, you can do it yourself.

here is how to get a free SSL certificate using certbot on ubuntu:

```bash
sudo apt install certbot
````

get certificates for the domain (canary.ravlykmail.com in this example):

```bash
sudo certbot certonly --standalone  -d canary.ravlykmail.com
cat <<EOF | sudo cron
````

(if you are running apache or nginx on that port, you need to use a slighly different command.)

you want to make sure the certificate is renewed automatically.
do that by creating the following file in /etc/cron.daily/certbot:

/etc/cron.daily/certbot:
```bash
#!/bin/bash
certbot renew > /home/ddd/cert.renew.log 2>&1
````

make sure the script is executable:

```bash
sudo chmod +x /etc/cron.daily/certbot
```

## setup postfix mail relay

install postfix:

```bash
sudo apt install postfix
```

configure it as an "internet site" with the system mail name set to `canary.ravlykmail.com`.

edit `/etc/postfix/main.cf` and `/etc/postfix/master.cf` to make the following changes:

the changes to `main.cf` are about configuring TLS, setting the hostname (remember to change canary.ravlykmail.com to your domain name),
and restricting the interfaces and protocols postfix listens on and uses.
all incoming mail will be from the k9 server running on the same machine, so we don't need to listen on all interfaces,
and we don't want to allow external servers to use us as a relay.
the provider we are using doesn't have PTR records for IPv6 addresses, so we will disable IPv6 in postfix.
you can change to all if IPv6 works for you.

**NOTE: the 

`/etc/postfix/main.cf`
```
@@ -24,8 +24,8 @@ compatibility_level = 3.6
 
 
 # TLS parameters
-smtpd_tls_cert_file=/etc/ssl/certs/ssl-cert-snakeoil.pem
-smtpd_tls_key_file=/etc/ssl/private/ssl-cert-snakeoil.key
+smtpd_tls_cert_file=/etc/letsencrypt/live/canary.ravlykmail.com/fullchain.pem
+smtpd_tls_key_file=/etc/letsencrypt/live/canary.ravlykmail.com/privkey.pem
 smtpd_tls_security_level=may
 
 smtp_tls_CApath=/etc/ssl/certs
@@ -33,15 +33,15 @@ smtp_tls_security_level=may
 smtp_tls_session_cache_database = btree:${data_directory}/smtp_scache
 
 
-smtpd_relay_restrictions = permit_mynetworks permit_sasl_authenticated defer_unauth_destination
-myhostname = ubuntu
+smtpd_relay_restrictions = permit_mynetworks
+myhostname = canary.ravlykmail.com
 alias_maps = hash:/etc/aliases
 alias_database = hash:/etc/aliases
 myorigin = /etc/mailname
-mydestination = $myhostname, canary.ravlykmail.com, ubuntu, localhost.localdomain, localhost
+mydestination = $myhostname, canary.ravlykmail.com
 relayhost = 
 mynetworks = 127.0.0.0/8 [::ffff:127.0.0.0]/104 [::1]/128
 mailbox_size_limit = 0
 recipient_delimiter = +
-inet_interfaces = all
-inet_protocols = all
+inet_interfaces = loopback-only
+inet_protocols = ipv4
```

in `master.cf`, we will change the port postfix listens on from 25 to 2525, since k9 will be listening on port 25.
k9 will then forward the mail to postfix on port 2525 for delivery.
(k9's configuration file sets this port with the `smtp.relay.port` property.)

`/etc/postfix/master.cf`
```
@@ -9,7 +9,7 @@
 # service type  private unpriv  chroot  wakeup  maxproc command + args
 #               (yes)   (yes)   (no)    (never) (100)
 # ==========================================================================
-smtp      inet  n       -       y       -       -       smtpd
+2525      inet  n       -       y       -       -       smtpd
 #smtp      inet  n       -       y       -       1       postscreen
 #smtpd     pass  -       -       y       -       -       smtpd
 #dnsblog   unix  -       -       y       -       0       dnsblog
 ```

after making the changes, restart postfix and enable it to start on boot:

```bash
sudo systemctl restart postfix
sudo systemctl enable postfix
```

## create a ddd user with database access

* create the user `ddd` with a password of your choice.
    ```
    adduser --disabled-password --shell /bin/bash --gecos "discdd account" ddd
    ```
* give the user `ddd` with password `MYSQL_PASSWORD` (make it different from other passwords you use!) access to the mysql database.
    ```
    # mysql -u root
    CREATE USER 'ddd'@'localhost' IDENTIFIED BY 'MYSQL_PASSWORD';
    GRANT ALL PRIVILEGES ON *.* TO 'ddd'@'localhost';
    FLUSH PRIVILEGES;
    create database dtn_server_db;
    create database k9_adapter;
    ```

## give the ddd user the ability to manage its own systemd services

create the following `99-ddd-services` file in `/etc/sudoers.d/`:

`/etc/sudoers.d/99-ddd-services`:
```bash
ddd ALL=(ALL) NOPASSWD: /bin/systemctl restart bundleserver
ddd ALL=(ALL) NOPASSWD: /bin/systemctl restart k9
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

## setup the systemd services

### create the systemd service files

in the ddd home directory directory, create a `systemd` directory with following service files:

`systemd/bundleserver.service`:
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

`systemd/k9.service`:
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

***AS ROOT YOU NEED TO ENABLE THESE SERVICES:***

```bash
sudo systemctl enable /home/ddd/systemd/bundleserver.service
sudo systemctl enable /home/ddd/systemd/k9.service
```

***DO NOT START THEM YET, WE NEED TO SET UP THE CONFIGURATION FILES FIRST AND DEPLOY THE CODE.***

### create the configuration files

`systemd/bundleserver.cfg`:
```properties
bundle-server.bundle-store-root = /home/ddd/bundleserver-data
spring.datasource.username = ddd
spring.datasource.password = MYSQL_PASSWORD
serviceadapter.datacheck.interval = 30s
```

`systemd/k9.cfg`:
```properties
spring.datasource.username=ddd
spring.datasource.password=MYSQL_PASSWORD
adapter-server.root-dir=/home/ddd/k9-data
bundle-server.url=127.0.0.1:7778
smtp.localDomain=canary.ravlykmail.com
smtp.relay.host=127.0.0.1
smtp.relay.port=2525
smtp.localPort=25
```

### make the data directories and set up the server keys

```bash
mkdir -p ~/bundleserver-data/BundleSecurity/Keys/Server
mkdir -p ~/k9-data
```

YOU MUST NOW COPY THE SERVER KEYS TO `~/bundleserver-data/BundleSecurity/Keys/Server` HOW YOU GET THOSE KEYS IS OUTSIDE THE SCOPE OF THIS DOCUMENT.
## set up the deployment script

create a `deploy.sh` script in the `systemd` directory with the following content:

`systemd/deploy.sh`:
```bash
#!/bin/bash
set -e
exec &> ~/deploy.log

# use flock so that we don't have overlapping builds
exec 200<> ~/deploy.lock
flock -w 200 200

# Do a fresh clone
cd ~/git
rm -rf DDD
git clone --depth 1 https://github.com/SJSU-CS-systems-group/DDD

# go to the project directory
cd DDD

# Recompile the project
mvn clean install -Dmaven.test.skip=true

# copy the files
cp ~/git/DDD/bundleserver/target/bundleserver-*.jar ~/systemd/bundleserver.jar
cp ~/git/DDD/apps/k9/server/target/k9-*.jar ~/systemd/k9.jar


sudo systemctl restart k9
sudo systemctl restart bundleserver
```

### enable and start the services

run the following commands as ddd to build the code and start the services:

```bash
/home/ddd/systemd/deploy.sh
```

### add k9 to the application list

once everything is running, add the k9 application to the list of applications in the BundleServer.

```bash
 java -jar bundleserver.jar bundleserver.cfg appids update net.discdd.mail 127.0.0.1:9091
```
