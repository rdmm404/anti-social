FROM docker.io/library/golang:1.27.3-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /anti-social ./cmd/anti-social

# tzdata so TZ picks the budget day's zone; ca-certificates for a TLS database such as Neon.
FROM docker.io/library/alpine:3.22
RUN apk add --no-cache tzdata ca-certificates
COPY --from=build /anti-social /usr/local/bin/anti-social
USER 65534:65534
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["anti-social"]
