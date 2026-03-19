FROM golang:1.26.0-alpine3.23 AS app-build

WORKDIR /build

RUN apk --no-cache add upx

COPY go.mod go.sum ./
RUN go mod download

COPY . /build

RUN CGO_ENABLED=0 go build -ldflags="-w -s" -o book-recon ./cmd/book-recon/ && \
    upx -9 --lzma book-recon && \
    chmod +x book-recon

FROM alpine:3.23.3

WORKDIR /app

RUN addgroup -g 10001 book-recon && \
    adduser -h /app -D -u 10001 -G book-recon book-recon && \
    chmod 700 /app && \
    chown -R book-recon: /app

COPY --from=app-build /build/book-recon /app/book-recon

RUN chown -R book-recon: /app && chmod +x /app/book-recon

USER book-recon

CMD ["/app/book-recon"]
