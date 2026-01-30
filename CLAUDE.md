# Project Description

This project uses a set of Android Apps and a server running on the internet
to physically transport data from disconnected android phones to the internet.
Our first target application is email.

# Applications

* the core Android apps: BundleClient and BundleTransport
* the core server app: bundleserver
* library modules used by the core apps: bundle-core and serviceadapter-core
* the apps that run on top of the server are under the apps directory.
  the apps will have a client component and a server component (called the ServiceAdapter).

# First application
our first target application is email and we have modified K9 (the opensource android email client) to work with DDD: https://github.com/SJSU-CS-systems-group/DDD-thunderbird-android

# Building

the server apps are build with maven, and the android apps are built with gradle.
we recommend using intellij and android studio for development.
check out this repo directly into the relevant IDE. the IDE will automatically recognize the gradle and maven projects.

**for the Android apps, you will first need to run maven install to get the bundle-core library into your local maven repository.**

# Languages

we use kotlin for UI development and Java for everything else.
try to keep as much of the logic in Java as possible. this helps with integration testing.

# Setting up the development environment

we use intellij to develop the shared logic between clients and server and for the BundleServer and adapters.
we use AndroidStudio to develop the Android client apps.
tragically, we use two build systems: maven in intellij and gradle for AndroidStudio.
you don't have to use intellij and AndroidStudio, but the environment is tailored for those two IDEs.

## Building

you must first `mvn install` before compiling with AndroidStudio.
everything uses bundle-core and that is built with maven.

**bundle-core uses our github maven package repo, so you have to set up settings.xml in your .m2 directory!**
our package repo is public, but even for public repos, you must set up authentication for github.
here is an example settings.xml:

```
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                      http://maven.apache.org/xsd/settings-1.0.0.xsd">
<servers>
  <server>
    <id>github</id> <!-- Match ID -->
    <username>USERNAME</username>
    <password>GITHUB_PERSONAL_ACCESS_TOKEN</password>
  </server>
</servers>
</settings>
```

