ARG GOLANG_IMAGE=golang:1.13.15-alpine@sha256:e74b83b94d499cb34c7edf55fbdff9d3cfabd238a35f9cb1b59d0e74f50b1ddd
ARG RUNTIME_IMAGE=alpine:3.12.1@sha256:c0e9560cda118f9ec63ddefb4a173a2b2a0347082d7dff7dc14272e7841a5b5a

FROM ${GOLANG_IMAGE} AS build

ARG UPSTREAM_COMMIT=4975a569f7be11a89f9c381494f42ccf55fd49d3
WORKDIR /src

RUN apk add --no-cache ca-certificates git \
    && git init \
    && git remote add origin https://github.com/ukarim/smscsim.git \
    && git fetch --depth 1 origin "${UPSTREAM_COMMIT}" \
    && git checkout --detach FETCH_HEAD \
    && test "$(git rev-parse HEAD)" = "${UPSTREAM_COMMIT}"

COPY patches/0001-empty-deliver-sm-service-type.patch /tmp/0001-empty-deliver-sm-service-type.patch
RUN git apply --check --unidiff-zero /tmp/0001-empty-deliver-sm-service-type.patch \
    && git apply --unidiff-zero /tmp/0001-empty-deliver-sm-service-type.patch

COPY service_type_test.go /src/service_type_test.go
RUN CGO_ENABLED=0 go test -count=1 -run '^TestDeliverSmServiceTypeIsEmpty$' .
RUN CGO_ENABLED=0 go build -trimpath -o /out/smscsim .

FROM ${RUNTIME_IMAGE}

LABEL org.opencontainers.image.source="https://github.com/ukarim/smscsim" \
      org.opencontainers.image.revision="4975a569f7be11a89f9c381494f42ccf55fd49d3" \
      org.opencontainers.image.licenses="MIT" \
      io.system-proof.fixture.patch="patches/0001-empty-deliver-sm-service-type.patch"

COPY --from=build /out/smscsim /app/smscsim
COPY --from=build /src/LICENSE /licenses/ukarim-smscsim/LICENSE

USER 10001
EXPOSE 2775 12775
ENTRYPOINT ["/app/smscsim"]
