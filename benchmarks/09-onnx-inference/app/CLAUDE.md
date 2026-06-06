# CLAUDE.md — benchmark-1-onnx-inference-app

## Overview

A production-grade Spring Boot REST service that performs ML inference using
ONNX Runtime. Shippable as a standalone Docker container. No benchmarking
code in this project.

## JDK Target: 17

## Tech Stack

- Java 17, Spring Boot 3.3, ONNX Runtime 1.18, Maven

## Project Structure

```
benchmark-1-onnx-inference-app/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── src/main/java/com/palaashatri/bench/onnx/
│   ├── OnnxInferenceApplication.java
│   ├── config/
│   │   ├── OnnxSessionConfig.java
│   │   └── ModelProperties.java
│   ├── controller/
│   │   ├── TextInferenceController.java
│   │   └── ImageInferenceController.java
│   ├── service/
│   │   ├── TextInferenceService.java
│   │   ├── ImageInferenceService.java
│   │   └── TokenizerService.java
│   ├── model/
│   │   ├── InferenceRequest.java
│   │   ├── InferenceResponse.java
│   │   ├── ClassificationResult.java
│   │   └── TokenizedInput.java
│   └── preprocessing/
│       ├── TextPreprocessor.java
│       ├── ImagePreprocessor.java
│       └── SoftmaxPostprocessor.java
├── src/main/resources/
│   ├── application.yml
│   ├── models/          # .onnx files go here
│   └── vocab/           # tokenizer vocab files
└── src/test/java/...
```

## Dependencies (pom.xml)

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>

<properties>
    <java.version>17</java.version>
    <onnxruntime.version>1.18.0</onnxruntime.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>com.microsoft.onnxruntime</groupId>
        <artifactId>onnxruntime</artifactId>
        <version>${onnxruntime.version}</version>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
</dependencies>
```

## Key Implementation Details

### ONNX Session Configuration

`OnnxSessionConfig` creates one `OrtEnvironment` and one `OrtSession` as
singleton beans (with `destroyMethod = "close"`). Thread count is
configurable via `${onnx.threads.intra:4}`. Optimisation level is set to
`ALL_OPT`.

### Pure-Java WordPiece Tokenizer (NO HuggingFace dependency)

`TokenizerService` loads a vocab file on startup and implements WordPiece
tokenisation in pure Java. Special token IDs: CLS=101, SEP=102, PAD=0,
UNK=100. Max sequence length: 128. This is intentional — pure-Java
tokenisation maximises JIT compilation surface.

### Inference Service

`TextInferenceService.classify(String text)` tokenises the input, creates
`OnnxTensor` instances for `input_ids` and `attention_mask`, runs the ONNX
session, applies softmax via `SoftmaxPostprocessor`, and returns an
`InferenceResponse`. Tensors are closed in a `finally` block.

### Records

```java
public record InferenceRequest(String text) {}

public record InferenceResponse(
    int predictedClass,
    float confidence,
    float[] probabilities
) {}

public record TokenizedInput(long[] inputIds, long[] attentionMask) {}
```

### application.yml

```yaml
server:
  port: 8080

onnx:
  model:
    path: ${ONNX_MODEL_PATH:src/main/resources/models/distilbert-sst2.onnx}
  vocab:
    path: ${ONNX_VOCAB_PATH:src/main/resources/vocab/vocab.txt}
  threads:
    intra: ${ONNX_INTRA_THREADS:4}

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  metrics:
    export:
      prometheus:
        enabled: true
```

### Dockerfile

```dockerfile
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY target/*.jar app.jar
COPY src/main/resources/models/ /app/models/
COPY src/main/resources/vocab/ /app/vocab/
ENV ONNX_MODEL_PATH=/app/models/distilbert-sst2.onnx
ENV ONNX_VOCAB_PATH=/app/vocab/vocab.txt
ENV JAVA_OPTS="-Xms512m -Xmx1g -XX:+UseG1GC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### Model Download

Run `scripts/download-models.sh` (in this directory). It exports
`distilbert-base-uncased-finetuned-sst-2-english` from HuggingFace to ONNX
using `optimum-cli export onnx` and downloads the corresponding `vocab.txt`.

## Build & Run

```bash
mvn package -DskipTests          # compile
mvn test                          # unit tests
mvn spring-boot:run               # dev mode
# or:
java $JAVA_OPTS -jar target/onnx-inference-app.jar
```

## External Contract

HTTP endpoint consumed by the harness:

| Method | Path                          | Body / Response            |
|--------|-------------------------------|----------------------------|
| POST   | /api/v1/inference/classify    | `InferenceRequest` → `InferenceResponse` |
| GET    | /api/v1/inference/health      | `{"status":"UP"}`          |
| GET    | /actuator/prometheus          | Prometheus metrics scrape  |

## What NOT to Do

- Do NOT add any JMH, Gatling, or benchmarking dependencies to this project.
- Do NOT add HuggingFace Java tokenizer libs — implement WordPiece in pure
  Java (more JIT work = better benchmark signal).
- Do NOT use async/reactive — keep it servlet-based to stress the
  thread-per-request model.
- Do NOT cache inference results — every request must run the full pipeline.
- Do NOT use the ONNX GPU provider — CPU only so JVM overhead is visible.
