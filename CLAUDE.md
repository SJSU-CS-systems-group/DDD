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

# Deployment

The bundleserver is deployed via a GitHub Actions pipeline (`.github/workflows/deploy.yml`) triggered on pushes to `main` (excluding Android-only and CI-only changes). It can also be triggered manually from any branch via the "Run workflow" button in the Actions UI (`workflow_dispatch`).

## Pipeline stages

1. **build** — builds all Maven modules on a GitHub-hosted runner (`ubuntu-latest`) and uploads bundleserver, k9, and CLI jars as artifacts
2. **deploy-canary** — SCPs jars to the canary server and restarts `bundleserver` and `k9` systemd services
3. **test-canary** — runs CLI sanity tests against the canary server (initialize client, add ADU, exchange)
4. **deploy-production** — requires manual approval in the GitHub Actions UI, then SCPs jars to production and restarts services

## Required GitHub setup

Two GitHub Environments must be configured in repo Settings → Environments:
- **canary** — no protection rules
- **production** — "Required reviewers" protection rule enabled

Each environment needs these secrets:
- `DEPLOY_SSH_HOST` — server IP or hostname
- `DEPLOY_SSH_USER` — SSH user for deployment
- `DEPLOY_SSH_KEY` — SSH private key for authentication (generated once, stored as a secret)

## One-time SSH setup

SSH access to both canary and production servers must be configured once:

1. Generate an SSH key pair on any machine:
   ```bash
   ssh-keygen -t ed25519 -f deploy_key -N ""
   ```
2. Add the public key (`deploy_key.pub`) to `~/.ssh/authorized_keys` on both the canary and production servers
3. Add the private key (`deploy_key`) as the `DEPLOY_SSH_KEY` secret in both GitHub Environments — the workflow writes it to disk on each run

## One-time setup on canary server

The canary server must be set up to mirror the production server:

1. Install Java 21
2. Set up MySQL and create the `dtn_server_db` database
3. Generate BundleSecurity server keys (same process as production) and place them at the configured path
4. Create systemd service files for `bundleserver` and `k9` (same as production)
5. Configure `application.yml` with canary-specific DB credentials, paths, and ports
6. Ensure the deploy user has `sudo` access to restart the systemd services

## CLI sanity test commands

The CLI tool (`apps/cli/target/cli-*.jar`) is used for canary testing:

```bash
# Initialize client storage with server keys and address
java -jar cli.jar bc initializeStorage <dir> --server-keys <keys-dir> --server <host>:<port>

# Add a test ADU
java -jar cli.jar bc addAdu <dir> <appid> <adu-file>

# Perform exchange (upload + download bundles)
java -jar cli.jar bc exchange <dir>
```

If any step exits non-zero, the test-canary job fails and blocks the production approval gate.

