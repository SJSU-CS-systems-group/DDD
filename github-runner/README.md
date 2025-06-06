# github runner setup for DDD

having a docker runner with android SDK, jetbrains formatter, and emulator all ready to go speeds up builds a lot.
what follows is instructions for setting up the Docker image and then running it as a github action runner.

## the Docker file

the docker file can be built as is with:

```
docker build -t gha-android-runner .
```

## starting up Docker

we need to configure the docker container first, so we will execute it with bash as the entry point.

```
sudo docker run -it \
  --privileged -e RUNNER_ALLOW_RUNASROOT=1 --name gha-android-runner \
  -v gha-runner-data:/home/github-runner/actions-runner \
  --entrypoint /bin/bash gha-android-runner
```

go to https://github.com/SJSU-CS-systems-group/DDD/settings/actions/runners and create a new runner.
use the token from the ./config.sh line in the example script you see as TOKEN_FROM_GITHUB_NEW_RUNNER in the script below.

in the bash shell of the docker container, do the following:

```
cd actions-runner
./config.sh --url https://github.com/SJSU-CS-systems-group/DDD --name RUNNER_NAME --token TOKEN_FROM_GITHUB_NEW_RUNNER --labels android
```

we now need to delete and rerun the container to use the default entry point:


```
sudo docker stop gha-android-runner
sudo docker rm gha-android-runner
sudo docker run -d --restart always \
  --privileged -e RUNNER_ALLOW_RUNASROOT=1 --name gha-android-runner \
  -v gha-runner-data:/home/github-runner/actions-runner \
  gha-android-runner
```
