# CLAUDE.md — benchmark-4-trading-gateway-app

## Overview

A simulated high-frequency trading order gateway. Accepts orders via gRPC,
validates through 8 rule validators, maintains an in-memory order book, and
produces an audit trail. Designed for sub-millisecond latency measurement.

## JDK Target: 25

Value classes (JEP 401) for zero-allocation order DTOs; virtual threads for
audit I/O; unnamed patterns in switch expressions.

## Tech Stack

- Java 25, gRPC-Java 1.66, HdrHistogram, LMAX Disruptor 4.0,
  Chronicle Queue (audit trail), Maven

## Project Structure

```
benchmark-4-trading-gateway-app/
├── pom.xml
├── Dockerfile
├── src/main/proto/
│   └── trading.proto
├── src/main/java/com/palaashatri/bench/trading/
│   ├── TradingGatewayApplication.java
│   ├── server/
│   │   └── TradingGrpcServer.java
│   ├── model/
│   │   ├── Order.java           # value class
│   │   ├── Quote.java           # value class
│   │   ├── Fill.java            # value class
│   │   ├── OrderSide.java
│   │   └── OrderType.java
│   ├── orderbook/
│   │   ├── OrderBook.java
│   │   ├── PriceLevel.java
│   │   └── MatchingEngine.java
│   ├── validation/
│   │   ├── OrderValidator.java          # sealed interface
│   │   ├── PriceLimitValidator.java
│   │   ├── QuantityValidator.java
│   │   ├── SymbolValidator.java
│   │   ├── AccountValidator.java
│   │   ├── RiskLimitValidator.java
│   │   ├── PositionValidator.java
│   │   ├── DuplicateOrderValidator.java
│   │   └── MarketHoursValidator.java
│   ├── risk/
│   │   └── RiskManager.java
│   ├── audit/
│   │   └── AuditTrailWriter.java
│   └── metrics/
│       └── LatencyRecorder.java
└── src/main/resources/
    ├── application.properties
    └── symbols.csv
```

## Key Implementation Details

### Value Classes (JDK 25 — zero allocation)

`Order`, `Fill`, and `Quote` are declared with `public value class`. They
carry no object header and generate no heap allocation on the critical path.
Prices are stored as `long` nanos (not `BigDecimal`) to eliminate boxing and
GC pressure.

### Validation Chain (sealed interface)

`OrderValidator` is a sealed interface permitting 8 validators. Each
implements `ValidationResult validate(Order order)`, where `ValidationResult`
is a nested record with a static `OK` constant. `TradingGrpcServer` iterates
the list and short-circuits on the first rejection.

### Order Book

`OrderBook` holds two `TreeMap<Long, PriceLevel>` structures (bids sorted
descending, asks sorted ascending). `submit(Order)` uses a switch expression
on `order.side()` to select the matching side and walks the book iterator.
Unmatched remainder is added to the resting side. **No synchronized blocks
on the hot path** — the order book is protected by the Disruptor or
caller-side serialisation.

### gRPC Service

`TradingGrpcServer.submitOrder`:
1. Record `System.nanoTime()`.
2. Map proto `OrderRequest` → value-class `Order`.
3. Run all 8 validators; reject immediately on first failure.
4. Submit to the per-symbol `OrderBook`.
5. Start a virtual thread for `AuditTrailWriter.write(order, fills)`.
6. Record latency nanos in `LatencyRecorder` (backed by HdrHistogram).
7. Reply with `OrderResponse`.

## Build & Run

```bash
mvn package -DskipTests          # requires JDK 25 toolchain
java --enable-preview -jar target/trading-gateway.jar
```

gRPC port: 9090 (default). Prometheus metrics: 8080/metrics.

## What NOT to Do

- Do NOT use `BigDecimal` for prices — use `long` nanos (critical for latency).
- Do NOT use Spring Boot — raw gRPC server for minimum framework overhead.
- Do NOT add any test harness code.
- Do NOT use `synchronized` blocks on the hot path — use Disruptor or
  lock-free structures.
- Do NOT use `java.util.logging` — use `System.Logger` or nothing on the hot
  path.
