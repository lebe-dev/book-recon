# --- Variables ---

version := `cat cmd/book-recon/main.go | grep 'Version' | head -1 | cut -d '"' -f 2`
imageName := 'tinyops/book-recon'

default:
    @just --list

# --- Utility ---
cleanup:
    rm -rf bin/

# --- Dependencies ---
bump-deps:
    go get -u ./...
    go mod tidy

# --- Build ---
build: format
    go build -o bin/book-recon ./cmd/book-recon

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

release: release-image

deploy:
    ssh kaiman 'cd /opt/book-recon && IMAGE_TAG={{ version }} docker compose pull && docker compose down && IMAGE_TAG={{ version }} docker compose up -d'

port-forward-jackett:
    ssh -N -L 9117:localhost:9117 kaiman
