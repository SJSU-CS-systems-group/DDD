FROM ubuntu:24.04

RUN echo "build with: docker build -t gha-android-runner ."

ENV DEBIAN_FRONTEND=noninteractive

# Install essential packages and dependencies
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    maven \
    git curl wget unzip zip wget tar sudo software-properties-common \
    libpulse0 libglu1-mesa xvfb qemu-kvm \
    lib32stdc++6 lib32z1 \
    libssl-dev libcurl4 libicu-dev \
    jq \
    && rm -rf /var/lib/apt/lists/*

# Create a github-runner user
RUN useradd -m github-runner && echo "github-runner ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers
USER github-runner
WORKDIR /home/github-runner
ENV HOME=/home/github-runner

# Setup Android SDK
ENV ANDROID_HOME=${HOME}/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator

RUN mkdir -p $ANDROID_HOME/cmdline-tools
RUN curl -sSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o android-cmdline-tools.zip && \
    unzip android-cmdline-tools.zip && \
    mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest && \
    rm android-cmdline-tools.zip

# Accept licenses and install required SDK components
# we are building against 34 but we want to test with a 33 image
RUN yes | sdkmanager --licenses && \
    sdkmanager --update && \
    sdkmanager "platform-tools" "emulator" \
        "platforms;android-34" \
        "build-tools;34.0.0" \
        "system-images;android-33;google_apis;x86_64"

# Create Android emulator AVD
RUN echo "no" | avdmanager create avd -n test_avd-33 -k "system-images;android-33;google_apis;x86_64"

# -------- IntelliJ IDEA Community Edition --------
ENV INTELLIJ_VERSION=2023.3.5
ENV INTELLIJ_HOME=${HOME}/intellij
ENV PATH="${INTELLIJ_HOME}/bin:$PATH"

RUN wget -q https://download.jetbrains.com/idea/ideaIC-${INTELLIJ_VERSION}.tar.gz && \
    mkdir -p ${INTELLIJ_HOME} && \
    tar -xzf ideaIC-${INTELLIJ_VERSION}.tar.gz -C ${INTELLIJ_HOME} --strip-components=1 && \
    rm ideaIC-${INTELLIJ_VERSION}.tar.gz

# Install GitHub Actions Runner
RUN mkdir actions-runner && cd actions-runner && \
    curl -O -L https://github.com/actions/runner/releases/download/v2.317.0/actions-runner-linux-x64-2.317.0.tar.gz && \
    tar xzf actions-runner-linux-x64-2.317.0.tar.gz && \
    rm actions-runner-linux-x64-2.317.0.tar.gz

# Set entrypoint to keep runner alive
ENTRYPOINT ["/home/github-runner/actions-runner/run.sh"]

