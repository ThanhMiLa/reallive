---
name: feign-client
description: Apply this repository's Feign API interface and client wrapper conventions when creating, refactoring, or reviewing Java HTTP clients between application microservices.
---

# Feign Client Convention

Separate the declarative HTTP contract from transport handling. Business services call a concrete client wrapper instead of calling a Feign interface or catching Feign exceptions directly.

## Structure and Naming

Keep the following files together under the owning feature's `client/<target>/` package, following [code-architecture](../code-architecture/SKILL.md):

| File | Responsibility |
| --- | --- |
| `<Target>ApiClient` | Interface annotated with `@FeignClient`; declares endpoints, HTTP methods, headers, request bodies and response types only. |
| `<Target>Client` | Concrete Spring component wrapping the API interface; prepares requests, supplies credentials, validates response structure and handles transport failures. |
| `<Target>ClientConfiguration` | Feign-specific settings such as timeout and retry policy. |
| Request/response records | Remote API contracts used by the interface and wrapper. |

The wrapper uses composition: inject `<Target>ApiClient`; do not implement it or manually implement the Feign proxy. Do not add another wrapper interface or `impl` package solely for symmetry. Use the repository's Lombok conventions from [code-style](../code-style/SKILL.md).

Apply this convention to the requested client, without migrating unrelated integrations. Provider/infrastructure integrations still belong in `adapter`; this skill does not change their ownership or require switching HTTP libraries.

## Responsibility Boundary

- Keep the Feign interface free of business logic, default-method error handling and configuration-secret lookup.
- In the wrapper, obtain internal tokens from configuration and construct the remote request DTO. Do not make services repeatedly supply integration credentials or catch `FeignException`.
- Validate the response fields the caller relies on, including a null response or missing required collections. A structurally invalid response is a failed integration result, not a successful empty response.
- Keep business rules in the service: repository access, transaction boundaries, job eligibility, accepted/rejected ID reconciliation, status transitions and retry scheduling.
- Keep HTTP calls outside database transactions when the use case requires short transactions. Adding a wrapper must not introduce a surrounding transaction.

## Failure Contract

Choose the wrapper's result according to the use case, and preserve that contract during refactoring:

- For thumbnail dispatch, return `Optional<ThumbnailBatchResponse>`. A valid response produces a value; HTTP/transport failures and malformed responses produce `Optional.empty()`. The service retains pending jobs and controls later same-ID resends.
- For operations whose failures must propagate to callers, translate transport failures using the existing `AppException(ErrorCode)` conventions in [exception-handling](../exception-handling/SKILL.md). Do not silently convert required dependency failures to absence or fabricated fallback data.
- An empty collection in a valid response can represent a successful operation with no items. Do not confuse it with a missing required field.
- A timeout does not establish whether the remote service applied the request. Do not infer that rollback, a new job ID or an immediate retry is safe.

Handle `FeignException` in the wrapper for HTTP failures and Feign transport errors. Handle other relevant integration exceptions there as well. The thumbnail wrapper also catches `RuntimeException` around request construction, the remote call and structural response validation; keep this catch scoped to that integration operation. Do not place downstream business processing or database updates inside it.

Avoid duplicate logging in the service and wrapper. Log operation, HTTP status when available, exception type and safe identifiers. Do not log raw exception messages, response bodies, request bodies, JWTs, internal tokens or credentials when they may expose secrets.

## Thumbnail Reference

The concrete reference in this repository is:

- `stream-service/src/main/java/com/reallive/streamservice/stream/client/thumbnail/ThumbnailWorkerApiClient.java`
- `stream-service/src/main/java/com/reallive/streamservice/stream/client/thumbnail/ThumbnailWorkerClient.java`
- `stream-service/src/main/java/com/reallive/streamservice/stream/client/thumbnail/ThumbnailWorkerClientConfiguration.java`

The service receives only the integration result and performs ACK reconciliation separately:

```java
private void dispatchBatch(List<ThumbnailJobPayload> payloads) {
    workerClient.dispatchBatch(payloads)
            .ifPresent(response -> applyAcceptedJobs(payloads, response));
}
```

For this integration, preserve configured connect/read timeouts (defaults 2/5 seconds) and `Retryer.NEVER_RETRY`; the scheduler owns resends. Other clients follow their own documented timeout, retry and idempotency policy rather than inheriting thumbnail defaults automatically.

## Verification

Test the wrapper with a mocked API interface: correct token/request, a valid response, HTTP failure, connection/timeout failure, relevant runtime/decoding failure and null/missing required response fields. Verify that failures follow the declared result contract and do not trigger hidden additional calls.

Keep tests for business ACK handling and state transitions in the service. When changing the Feign declaration or configuration, retain relevant HTTP contract tests for route, serialization and headers, and configuration tests for timeout/retry behavior.
