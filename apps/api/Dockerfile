FROM golang:1.25.1-alpine AS dependencies

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

FROM dependencies AS development

COPY . .
EXPOSE 8080
CMD ["go", "run", "./cmd/server"]

FROM development AS test

RUN apk add --no-cache build-base
RUN go test -race ./...

FROM development AS integration-test

CMD ["go", "test", "-tags=integration", "-p", "1", "./..."]

FROM development AS build

RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/vk-platform-api ./cmd/server

FROM alpine:3.22 AS runtime

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /out/vk-platform-api /usr/local/bin/vk-platform-api
USER app
EXPOSE 8080
ENTRYPOINT ["vk-platform-api"]
