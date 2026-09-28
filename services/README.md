# services/

Demo backend services that the gateway routes traffic to. **Added in Phase 2.**

A small Spring Boot app run as several instances (Service A, B, C on different ports), with
switches to simulate latency, HTTP 500s and crashes, so load balancing, retries and circuit
breaking can be demonstrated against real HTTP servers instead of mocks.
