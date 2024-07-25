# DDD

this repo is for the different parts of the disconnection data distribution project.

there are several parts to this project, including:

* the core Android apps: BundleClient and BundleTransport
* the core server app: bundleserver
* library modules used by the core apps: bundle-core and serviceadapter-core
* the apps that run on top of the server are under the apps directory.
  the apps will have a client component and a server component (called the ServiceAdapter).

the server apps are build with maven, and the android apps are built with gradle.
we recommend using intellij and android studio for development.
check out this repo directly into the relevant IDE. the IDE will automatically recognize the gradle and maven projects.

**for the Android apps, you will first need to run maven install to get the bundle-core library into your local maven repository.**

for more description about the project, here is a paper [EUC_2023_paper-3.pdf](https://github.com/SJSU-CS-systems-group/DDD/files/14874085/EUC_2023_paper-3.pdf) published in EUC 2023.
the following Master's reports relate to this project:


* the bundle transport: http://scholarworks.sjsu.edu/etd_projects/1214
* getting signal to work with DDD: http://scholarworks.sjsu.edu/etd_projects/1199 and https://scholarworks.sjsu.edu/etd_projects/1210
* security for DDD: http://scholarworks.sjsu.edu/etd_projects/1212
* data management: http://scholarworks.sjsu.edu/etd_projects/1211
* application interfaces: https://scholarworks.sjsu.edu/etd_projects/1223

