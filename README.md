# Virtual Bank Microservices

Educational static-REST baseline split into five independently runnable Spring Boot applications.

## Prerequisites

- Docker with Compose

## Run

Build and start all five applications and their four logically isolated databases:

```bash
docker compose up -d --build
```

Health endpoints are available at ports 8081 through 8085 respectively, at `/actuator/health`.

Startup order is PostgreSQL, Customer, Exchange Rate, Banking, Identity, and Transfer. Compose expresses these basic dependencies, although readiness and resilience are intentionally kept simple for the classroom baseline.

Inspect the stack or stop it with:

```bash
docker compose ps
docker compose down
```

Java 21 is only required when running or building the services outside containers.

For local development, one PostgreSQL container hosts four databases with separate owners and credentials. Services never share schemas or connect using another service's database user. The `docker/postgres/init-databases.sql` script provisions them when the `postgres-data` volume is first created.

## Build

```bash
./mvnw clean verify
```

## Security and end-to-end verification

Identity issues RS256 access tokens. Every public business API validates the signature, issuer, audience, expiration, and its own permissions/ownership rules. Identity additionally rejects tokens whose authentication version became stale after a password or role change.

Internal Customer, Banking, and Exchange Rate endpoints are currently reachable without a service credential. This is an explicit classroom limitation: service-to-service authentication is introduced in a later lesson and these paths must not be exposed outside the private application network in production.

With the Compose stack running, execute the black-box workflow (requires `curl` and `jq`):

```bash
./scripts/e2e.sh
```

It creates an isolated user, authenticates, opens and funds accounts, performs same-currency and FX transfers, replays an idempotent request, checks final balances, and verifies insufficient-funds, same-account, and unauthenticated rejection.

This checkpoint intentionally has no gateway, discovery, centralized configuration, messaging, retries, or tracing. Service URLs are fixed in each service's `application.properties` so later lessons can evolve the architecture one concern at a time.
