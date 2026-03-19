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

# --- Coverage ---
coverage:
    go test ./... -coverprofile=coverage.out
    go tool cover -func=coverage.out
    go tool cover -html=coverage.out -o coverage.html
    @echo "Coverage report generated at coverage.html"

# --- Format ---
format:
    go fmt ./...
    goimports -w .

# --- Development ---
run: build
    ./bin/book-recon

# --- Image ---
build-image: test lint
    docker build --progress=plain --platform linux/amd64 -t {{ imageName }}:{{ version }} .

push-image:
    docker push {{ imageName }}:{{ version }}

release-image: build-image push-image

release: release-image
