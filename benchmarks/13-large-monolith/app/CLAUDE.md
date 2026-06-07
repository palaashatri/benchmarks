# CLAUDE.md — benchmark-5-large-monolith-app


> **Current implementation status:** this directory currently contains a local smoke-test scaffold, not the full benchmark application described below. The scaffold exists so `run.sh build` / `run.sh test` and the harness/result pipeline can be exercised without external services. The full implementation described in this file remains the target and should replace the scaffold incrementally. See `../../../IMPLEMENTATION_STATUS.md`.

## Overview

A deliberately large Spring Boot monolith designed to have an extremely long
warm-up time. 500+ beans, 100+ JPA entities, complex business rules,
scheduled jobs. Represents enterprise Java applications like those at
Salesforce and Unity.

## JDK Target: 17

## Tech Stack

- Java 17, Spring Boot 3.3, Spring Data JPA, Hibernate 6,
  Spring Kafka, Spring Cache (Caffeine), PostgreSQL 16, Maven

## Project Structure

```
benchmark-5-large-monolith-app/
├── pom.xml
├── Dockerfile
├── src/main/java/com/palaashatri/bench/monolith/
│   ├── MonolithApplication.java
│   ├── customer/                   # 20+ entities, services, repos, controllers
│   │   ├── entity/
│   │   │   ├── Customer.java
│   │   │   ├── CustomerAddress.java
│   │   │   ├── CustomerPreference.java
│   │   │   ├── CustomerSegment.java
│   │   │   └── LoyaltyAccount.java
│   │   ├── service/CustomerService.java
│   │   ├── repository/CustomerRepository.java
│   │   └── controller/CustomerController.java
│   ├── product/                    # 20+ entities
│   │   ├── entity/
│   │   │   ├── Product.java
│   │   │   ├── ProductVariant.java
│   │   │   ├── ProductAttribute.java
│   │   │   ├── Category.java
│   │   │   ├── Brand.java
│   │   │   └── Supplier.java
│   │   ├── service/ProductService.java
│   │   └── controller/ProductController.java
│   ├── order/                      # 15+ entities
│   ├── inventory/                  # 10+ entities
│   ├── shipping/                   # 10+ entities
│   ├── payment/                    # 10+ entities
│   ├── analytics/                  # 10+ entities
│   ├── notification/               # 5+ entities
│   ├── rules/                      # 12 business rule classes
│   │   ├── RuleEngine.java
│   │   ├── BusinessRule.java       # sealed interface
│   │   ├── DiscountRule.java
│   │   ├── TaxRule.java
│   │   ├── ShippingRule.java
│   │   ├── FraudDetectionRule.java
│   │   ├── InventoryAllocationRule.java
│   │   ├── LoyaltyPointsRule.java
│   │   ├── ReturnPolicyRule.java
│   │   ├── PriceMatchRule.java
│   │   ├── BundleRule.java
│   │   ├── SubscriptionRule.java
│   │   ├── GiftWrapRule.java
│   │   └── ComplianceRule.java
│   ├── scheduler/                  # 6 scheduled jobs
│   │   ├── InventorySyncJob.java
│   │   ├── PriceUpdateJob.java
│   │   ├── ReportGenerationJob.java
│   │   ├── DataCleanupJob.java
│   │   ├── NotificationBatchJob.java
│   │   └── AnalyticsAggregationJob.java
│   └── config/
│       ├── JpaConfig.java
│       ├── CacheConfig.java
│       ├── KafkaConfig.java
│       └── SecurityConfig.java
└── src/main/resources/
    ├── application.yml
    └── db/migration/
        ├── V1__schema.sql          # 100+ tables
        └── V2__seed_data.sql       # 500K+ rows
```

## Key Design Principle

The goal is **massive classloading and JIT compilation surface area**. Every
module should have deep class hierarchies, polymorphic service calls, and
complex Hibernate mappings that force thousands of methods to be
JIT-compiled.

## Key Implementation Details

### Rule Engine (12 rules — maximum polymorphism)

`BusinessRule` is a sealed interface permitting 12 implementations (see
project structure above). Each implementation defines `RuleResult
evaluate(OrderContext context)`, `int priority()`, and `String name()`.

`RuleEngine` sorts rules by priority at construction time. `evaluate(context)`
applies all 12 rules in priority order and aggregates the results.

### Complex Hibernate Entities

Entities use `@OneToMany`, `@ManyToOne`, and `@OneToOne` relationships with
a mix of `FetchType.LAZY` and `CascadeType.ALL`. The `Customer` entity alone
has 30+ fields and relationships to `CustomerAddress`, `LoyaltyAccount`,
`CustomerSegment`, `CustomerPreference`, and `Order`. This forces Hibernate
to generate complex proxy hierarchies and session-level caches.

### Scheduled Jobs

Six `@Scheduled` jobs run at intervals of 30 s to 5 min:
- `InventorySyncJob` — syncs inventory levels.
- `PriceUpdateJob` — recalculates prices from rules.
- `ReportGenerationJob` — aggregates analytics.
- `DataCleanupJob` — purges stale records.
- `NotificationBatchJob` — sends batch notifications.
- `AnalyticsAggregationJob` — aggregates metrics.

These jobs add realistic background JIT work that competes with request
handling during warm-up.

## Build & Run

```bash
mvn package -DskipTests
mvn spring-boot:run
```

PostgreSQL, Redis, and Kafka must be running (see `docker-compose.yml`).

## What NOT to Do

- Do NOT add benchmark or test harness code.
- Do NOT remove domain modules to make the app smaller — the size IS the
  benchmark signal.
- Do NOT use lazy initialisation tricks to speed up startup — warm-up time
  is being measured.
- Do NOT use Spring WebFlux — keep it servlet-based.
