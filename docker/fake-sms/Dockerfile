FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod .
COPY main.go .
RUN go mod download && go mod tidy
RUN CGO_ENABLED=0 go build -o /fake-sms .

FROM alpine:3.21
COPY --from=build /fake-sms /fake-sms
EXPOSE 8080
ENTRYPOINT ["/fake-sms"]
