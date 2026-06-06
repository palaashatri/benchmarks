# CLAUDE.md — benchmark-2-microservices-fleet-app

## Overview

A 5-service microservices fleet simulating an e-commerce backend.
Each service is a standalone Spring Boot application packaged as a Docker
image. No benchmarking code — these are shippable production-style services.

## JDK Target: 17

## Tech Stack

- Java 17, Spring Boot 3.3, Spring Data JPA, Spring Data Redis,
  Spring Kafka, PostgreSQL 16, Redis 7, Apache Kafka 3.7, Maven (multi-module)

## Project Structure

```
benchmark-2-microservices-fleet-app/
├── pom.xml                         # parent POM
├── docker-compose.yml              # infrastructure only (Postgres, Redis, Kafka)
├── k8s/
│   ├── namespace.yml
│   ├── gateway-deployment.yml
│   ├── order-deployment.yml
│   ├── inventory-deployment.yml
│   ├── pricing-deployment.yml
│   └── audit-deployment.yml
├── gateway-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/palaashatri/bench/gateway/
│       ├── GatewayApplication.java
│       ├── controller/GatewayController.java
│       ├── client/OrderServiceClient.java
│       ├── client/InventoryServiceClient.java
│       ├── client/PricingServiceClient.java
│       └── config/RestClientConfig.java
├── order-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/palaashatri/bench/order/
│       ├── OrderApplication.java
│       ├── controller/OrderController.java
│       ├── service/OrderService.java
│       ├── entity/Order.java
│       ├── entity/OrderItem.java
│       ├── repository/OrderRepository.java
│       ├── event/OrderEventPublisher.java
│       └── dto/
│           ├── CreateOrderRequest.java
│           └── OrderResponse.java
├── inventory-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/palaashatri/bench/inventory/
│       ├── InventoryApplication.java
│       ├── controller/InventoryController.java
│       ├── service/InventoryService.java
│       ├── entity/InventoryItem.java
│       ├── repository/InventoryRepository.java
│       └── cache/InventoryCacheService.java
├── pricing-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/palaashatri/bench/pricing/
│       ├── PricingApplication.java
│       ├── controller/PricingController.java
│       ├── service/PricingService.java
│       ├── rules/
│       │   ├── PricingRule.java         # sealed interface
│       │   ├── BulkDiscountRule.java
│       │   ├── SeasonalRule.java
│       │   ├── LoyaltyRule.java
│       │   ├── FlashSaleRule.java
│       │   ├── BundleRule.java
│       │   ├── RegionalRule.java
│       │   ├── CouponRule.java
│       │   └── DynamicDemandRule.java
│       └── dto/PriceResponse.java
└── audit-service/
    ├── pom.xml
    ├── Dockerfile
    └── src/main/java/com/palaashatri/bench/audit/
        ├── AuditApplication.java
        ├── listener/OrderEventListener.java
        ├── service/AuditService.java
        ├── entity/AuditEntry.java
        └── repository/AuditRepository.java
```

## Key Implementation Details

### Pricing Rules (sealed interface — stresses JIT profiling)

`PricingRule` is a sealed interface permitting 8 implementations
(`BulkDiscountRule`, `SeasonalRule`, `LoyaltyRule`, `FlashSaleRule`,
`BundleRule`, `RegionalRule`, `CouponRule`, `DynamicDemandRule`). Every
pricing request evaluates all 8 rules — this is a megamorphic call site by
design, to surface JIT profiling differences.

`PricingContext` and `PriceAdjustment` are records.

### Order Service flow

`OrderService.createOrder`:
1. Check inventory availability for each item (REST call to inventory-service).
2. Calculate prices for each item (REST call to pricing-service).
3. Persist the order to PostgreSQL (`@Transactional`).
4. Publish `OrderCreatedEvent` to Kafka.
5. Return `OrderResponse`.

### Gateway Controller

`GatewayController` fans out to `OrderServiceClient`,
`InventoryServiceClient`, and `PricingServiceClient` using Spring's
`RestClient`. Exposes:
- `POST /api/v1/orders` → delegates to order-service
- `GET /api/v1/catalog/{productId}` → parallel calls to inventory + pricing

### Infrastructure docker-compose.yml

Starts PostgreSQL 16-alpine (`bench`/`bench`, db `microfleet`), Redis 7-alpine,
and Kafka 7.7.0 (KRaft mode, `CLUSTER_ID: benchmark-cluster-001`). Services
are wired together via Spring environment variables, not hardcoded URLs.

### Data Seeding

- 10,000 products in inventory with random stock levels (10–1000).
- Base prices for all products ($1–$500).
- Use Flyway or `schema.sql` + `data.sql` for DB initialisation.

## Build & Run

```bash
# From repo root:
mvn -pl docker-compose.yml up -d       # infra
mvn package -DskipTests                # build all modules
# Start each service image individually, or use the harness docker-compose
```

## What NOT to Do

- Do NOT add JMH or Gatling to any service.
- Do NOT use Spring WebFlux — keep it servlet-based.
- Do NOT skip Kafka (audit trail creates realistic async work).
- Do NOT hardcode service URLs — use Spring config + env vars for service
  discovery.
- Do NOT use in-memory H2 — use real PostgreSQL for realistic I/O.
