# CLAUDE.md — benchmark-3-autoscaling-api-app

## Overview

A Spring Boot product catalog API with a complex polymorphic pricing engine,
designed to be deployed on Kubernetes with HPA. Shippable standalone.

## JDK Target: 17

## Tech Stack

- Java 17, Spring Boot 3.3, Spring Data JPA, PostgreSQL 16, Redis 7, Maven

## Project Structure

```
benchmark-3-autoscaling-api-app/
├── pom.xml
├── Dockerfile
├── k8s/
│   ├── deployment.yml
│   ├── service.yml
│   ├── hpa.yml
│   └── configmap.yml
├── src/main/java/com/opthub/bench/catalog/
│   ├── CatalogApplication.java
│   ├── controller/
│   │   ├── ProductController.java
│   │   └── SearchController.java
│   ├── service/
│   │   ├── ProductService.java
│   │   ├── SearchService.java
│   │   └── RecommendationService.java
│   ├── pricing/
│   │   ├── PricingEngine.java
│   │   ├── PricingRule.java         # sealed interface
│   │   ├── rules/                   # 8 rule implementations
│   │   └── PricingContext.java
│   ├── entity/
│   │   ├── Product.java
│   │   ├── Category.java
│   │   ├── ProductAttribute.java
│   │   └── PriceHistory.java
│   ├── repository/
│   │   ├── ProductRepository.java
│   │   └── CategoryRepository.java
│   ├── cache/
│   │   └── ProductCacheService.java
│   └── dto/
│       ├── ProductResponse.java
│       ├── SearchRequest.java
│       └── SearchResponse.java
└── src/main/resources/
    ├── application.yml
    └── db/migration/
        ├── V1__schema.sql
        └── V2__seed_data.sql
```

## Key Implementation Details

### Pricing Engine

`PricingEngine` holds an immutable `List<PricingRule>`. For each request,
`price(Product, PricingContext)` iterates all 8 rules, calls `appliesTo(ctx)`
then `adjust(price, ctx)` on each, and returns a `PricedProduct`. Every
request evaluates ALL 8 rules — this is the megamorphic call site the
benchmark is designed to stress.

### Product Search with In-Memory Scoring

`SearchService.search` fetches product candidates from the DB by category and
attributes, then scores and sorts them in Java using `PricingEngine.price()`
and `Comparator.comparing(PricedProduct::relevanceScore)`. This keeps
JIT-heavy work in-process.

### HPA Configuration (k8s/hpa.yml)

- `minReplicas: 2`, `maxReplicas: 20`
- CPU target: 60% average utilisation
- Scale-up: 4 pods per 30 s, stabilisation window 15 s

### Data Seeding (V2__seed_data.sql)

50,000 products across 200 categories. Base prices $1–$500 (random).
Generated via `generate_series` in PostgreSQL.

## Build & Run

```bash
mvn package -DskipTests
docker build -t catalog-api .
kubectl apply -f k8s/
```

Or for local dev:
```bash
mvn spring-boot:run
```

## What NOT to Do

- Do NOT add benchmark code to this project.
- Do NOT cache pricing results — every request must evaluate all rules.
- Do NOT use reactive/WebFlux.
- Do NOT pre-warm on startup — the cold start IS the benchmark signal.
