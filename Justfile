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

# Android Lint, detekt (new issues only) and copy-paste check; fails on any of them
android-lint: android-check
    cd android && ./gradlew --continue :app:lintDebug detekt cpdCheck

alias lint-android := android-lint

# detekt: complexity, code smells and Compose rules; only issues missing from the baseline fail
android-detekt: android-check
    cd android && ./gradlew detekt

# Rewrites android/config/detekt/baseline.xml with every current detekt issue
android-detekt-baseline: android-check
    cd android && ./gradlew detektBaseline

# Copy-paste in production code (PMD CPD); fails when duplicates are found. TOKENS is the minimum copy size
android-cpd TOKENS='100': android-check
    cd android && ./gradlew cpdCheck -PcpdMinimumTokens={{ TOKENS }}

# Line and branch coverage of :core and :app unit tests in one report (Kover)
android-coverage: android-check
    cd android && ./gradlew :koverHtmlReportUnit :koverXmlReportUnit :koverLogUnit
    @echo "Coverage report: android/build/reports/kover/htmlUnit/index.html"

# Every analysis at once, reports only: detekt, duplicates, coverage and Android Lint
android-quality: android-check
    cd android && ./gradlew --continue detekt cpdReport :koverHtmlReportUnit :koverXmlReportUnit :koverLogUnit :app:lintDebug
    @echo "Reports:"
    @echo "  detekt:   android/build/reports/detekt/detekt.html"
    @echo "  CPD:      android/build/reports/cpd/cpd.txt"
    @echo "  coverage: android/build/reports/kover/htmlUnit/index.html"
    @echo "  lint:     android/app/build/reports/lint-results-debug.html"

android-install: android-build
    adb install -r android/app/build/outputs/apk/debug/app-debug.apk

# Debug APK onto the phone connected over USB (adb -d), then starts the app
deploy-android: android-build
    adb -d install -r android/app/build/outputs/apk/debug/app-debug.apk
    adb -d shell am start -n ru.tinyops.android.bookrecon/ru.tinyops.android.bookrecon.MainActivity

# Tests, lints and coverage run first, so a release is never built from code that fails them.
# Release APK in bin/book-recon-<VERSION>.apk, signed when ANDROID_* signing variables are set
android-release: android-test android-lint android-coverage
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
