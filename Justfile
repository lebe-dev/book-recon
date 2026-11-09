# --- Variables ---

version := `cat VERSION`
imageName := 'tinyops/book-recon'

default:
    @just --list

# --- Utility ---
cleanup:
    rm -rf bin/

# --- Dependencies ---
bump-deps:
    go get -u ./...
    # restore pins that upstream anacrolix/torrent is not compatible with yet
    go get github.com/anacrolix/missinggo/v2@v2.10.0
    go get github.com/anacrolix/stm@v0.5.0
    go mod tidy
    go build ./...

# --- Build ---
build: format
    go build -ldflags="-X main.Version={{ version }}" -o bin/book-recon ./cmd/book-recon

# --- Lints ---
lint: format
    golangci-lint run ./...

# --- Tests ---
test *ARGS:
    go test ./... {{ ARGS }}

integration-tests:
    go test -tags integration -v ./internal/adapter/provider/royallib/...

# --- Coverage ---
coverage:
    go test ./... -coverprofile=coverage.out
    go tool cover -func=coverage.out
    go tool cover -html=coverage.out -o coverage.html
    @echo "Coverage report generated at coverage.html"

# --- Format ---
format:
    go fmt ./...

# --- Development ---
run: build
    ./bin/book-recon

# --- Image ---
build-image: test lint
    docker build --progress=plain --platform linux/amd64 -t {{ imageName }}:{{ version }} -t {{ imageName }}:latest .

push-image:
    docker push {{ imageName }}:{{ version }}
    docker push {{ imageName }}:latest

release-image: build-image push-image

# Builds the signed APK first, so missing signing fails before the image is pushed.
# Attach bin/book-recon-<VERSION>.apk to the GitHub release.
release: android-release-signed release-image

# Pins IMAGE_TAG in the server .env, so a manual `docker compose up` keeps this version
deploy:
    ssh kaiman 'cd /opt/book-recon && sed -i "/^IMAGE_TAG=/d" .env && echo "IMAGE_TAG={{ version }}" >> .env && docker compose pull book-recon && docker compose up -d && docker compose ps'

port-forward-jackett:
    ssh -N -L 9117:localhost:9117 kaiman

ssh:
    ssh kaiman

# --- Android ---
# Recipes run the Gradle wrapper in android/ and need JDK 17+ and the Android SDK.

[private]
android-check:
    @if [ -z "${ANDROID_HOME:-}" ]; then echo "ANDROID_HOME is not set: install the Android SDK and export ANDROID_HOME=/path/to/sdk" >&2; exit 1; fi
    @if [ ! -d "${ANDROID_HOME}" ]; then echo "ANDROID_HOME points to a missing directory: ${ANDROID_HOME}" >&2; exit 1; fi

# Debug APK in android/app/build/outputs/apk/debug
android-build: android-check
    cd android && ./gradlew :app:assembleDebug

# Tests of :core and unit tests of :app
android-test: android-check
    cd android && ./gradlew :core:test :app:testDebugUnitTest

android-lint: android-check
    cd android && ./gradlew :app:lintDebug

android-install: android-build
    adb install -r android/app/build/outputs/apk/debug/app-debug.apk

# Release APK in bin/book-recon-<VERSION>.apk, signed when ANDROID_* signing variables are set
android-release: android-check
    #!/usr/bin/env bash
    set -euo pipefail
    out=android/app/build/outputs/apk/release
    rm -rf "$out"
    (cd android && ./gradlew :app:assembleRelease)
    mkdir -p bin
    if [ -f "$out/app-release.apk" ]; then
        cp "$out/app-release.apk" "bin/book-recon-{{ version }}.apk"
        echo "Signed APK: bin/book-recon-{{ version }}.apk"
        exit 0
    fi
    cp "$out/app-release-unsigned.apk" "bin/book-recon-{{ version }}.apk"
    echo "WARNING: release signing is not configured, bin/book-recon-{{ version }}.apk is UNSIGNED and cannot be installed." >&2
    echo "Set ANDROID_KEYSTORE, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD in the environment or .env." >&2

# Release APK that must be signed: an unsigned APK cannot be installed or published
[private]
android-release-signed: android-release
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -f android/app/build/outputs/apk/release/app-release.apk ]; then exit 0; fi
    rm -f "bin/book-recon-{{ version }}.apk"
    echo "Release signing is not configured: set ANDROID_KEYSTORE, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD." >&2
    exit 1
