# DocMind — System Architecture & Developer Guide

DocMind is a production-ready, enterprise-grade **Retrieval-Augmented Generation (RAG) Knowledge Assistant** built on **Spring Boot 3.3.5**, **Spring AI 1.0.0**, and **LangGraph4j 1.8.17**.

This document details the system design, core components, agentic workflow orchestration, and operational guidelines for developers and system architects.

---

## 📚 Table of Contents
1. [System Overview](#1-system-overview)
2. [Multi-Module Project Structure](#2-multi-module-project-structure)
3. [Core Architectural Components](#3-core-architectural-components)
4. [Agentic Workflow Orchestration (LangGraph4j)](#4-agentic-workflow-orchestration-langgraph4j)
5. [Document Ingestion Pipeline](#5-document-ingestion-pipeline)
6. [Resilience & Observability](#6-resilience--observability)
7. [API Reference & Usage Guides](#7-api-reference--usage-guides)
8. [Local Development & Deployment](#8-local-development--deployment)

---

## 1. System Overview

Traditional RAG applications retrieve documents and feed them to an LLM in a single pass. If the retrieved context is noisy or the LLM generates an inaccurate answer, the user receives an incorrect response.

DocMind resolves this by utilizing an **Agentic Loop** (LLM-as-a-Judge validation with retry feedback):
1. **Semantic Search**: Retrieval of top-K relevant chunks from ChromaDB.
2. **Contextual Generation**: Answer generation using OpenAI's `gpt-4o`.
3. **Self-Correction & Validation**: A local `mistral` model running in Ollama assesses if the generated answer is grounded in the retrieved documents.
4. **Retry Loop**: If validation fails, the query is broadened, additional chunks are retrieved and appended to the context, and a new answer is generated.

```
                  ┌──────────────────────────────┐
                  │          User Query          │
                  └──────────────┬───────────────┘
                                 │
                                 ▼
                  ┌──────────────────────────────┐
                  │    [Node: Retrieval]         │◀────────────────┐
                  │    ChromaDB Similarity       │                 │
                  └──────────────┬───────────────┘                 │
                                 │                                 │
                                 ▼                                 │
                  ┌──────────────────────────────┐                 │
                  │    [Node: Generation]        │                 │ (Retry Loop)
                  │    OpenAI GPT-4o             │                 │
                  └──────────────┬───────────────┘                 │
                                 │                                 │
                                 ▼                                 │
                  ┌──────────────────────────────┐                 │
                  │    [Node: Validation]        │                 │
                  │    Ollama Mistral (Judge)    │                 │
                  └──────────────┬───────────────┘                 │
                                 │                                 │
                                 ├───────── Is Answer Invalid? ────┘
                                 │         (Within Max Retries)
                                 ▼
                  ┌──────────────────────────────┐
                  │          Response            │
                  └──────────────────────────────┘
```

---

## 2. Multi-Module Project Structure

The project is designed using clean architectural boundaries. By separating DTOs, business logic, ingestion processing, and API entry points into distinct modules, we prevent circular dependencies and enforce the **Separation of Concerns**.

```
docmind (Root Parent POM)
 ├── docmind-common     (Shared DTOs, Exceptions, and Constants; Zero Spring dependencies)
 ├── docmind-ingestion  (Tika parsing, ChunkingStrategy, OpenAI Embeddings, and ChromaDB VectorStore writer)
 ├── docmind-core       (LangGraph4j StateGraph, RagState, Retrieval/Generation/Validation nodes)
 └── docmind-api        (Spring Boot App, Query/Ingestion controllers, global error handlers, application.yml)
```

### Module Descriptions & Dependencies

| Module | Core Purpose | Primary Dependencies |
|--------|--------------|----------------------|
| `docmind-common` | Shared request/response objects, domain exceptions, and constants. Keeps API definitions separate from engine logic. | Lombok |
| `docmind-ingestion` | Reads documents in multiple formats, splits them into semantic chunks, generates vector embeddings, and indexes them in ChromaDB. | Apache Tika, Spring AI OpenAI, Spring AI ChromaDB |
| `docmind-core` | Defines the stateful orchestration graph, custom state channels, and execution nodes. Integrates Resilience4j for LLM failover. | LangGraph4j, Spring AI OpenAI & Ollama, Resilience4j |
| `docmind-api` | Spring Boot main class, REST Controller layer, OpenApi/Swagger, Actuator endpoints, and application properties configuration. | Spring Boot Starter Web, Spring Boot Validation, Micrometer Prometheus |

---

## 3. Core Architectural Components

### 3.1 com.docmind.common (DTOs)
- **`QueryRequest`**: Enforces JSR-380 input validation for queries (e.g., non-empty question strings, optional filters).
- **`QueryResponse`**: Returns the final answer, source citations (text chunks and metadata), retry statistics, and diagnostic metrics (model used, latency).
- **`ApiError`**: Implements RFC 7807 (Problem Details for HTTP APIs) to provide structured error responses during validation or runtime failures.

### 3.2 com.docmind.ingestion (Document Pipelines)
- **`ChunkingStrategy`**: Swappable chunking interface implementing the **Open/Closed Principle**. 
- **`FixedSizeChunkingStrategy`**: Splits text into fixed token-like segments with a configurable overlap to maintain semantic context across chunks.
- **`DocumentIngestionService`**: Orchestrates parsing (via Apache Tika), chunking, embedding generation (via OpenAI `text-embedding-3-small`), and writing to ChromaDB.

### 3.3 com.docmind.core (Agentic Engine)
- **`RagState`**: State container inheriting from LangGraph4j's `AgentState`. Configured with custom state merging channels.
- **`RetrievalNode`**: Executes semantic similarity queries against ChromaDB. Relies on Spring AI's `SearchRequest`.
- **`GenerationNode`**: Constructs prompts combining retrieved context with the user's question, then requests completion via OpenAI.
- **`ValidationNode`**: Validates generated answers using a local Ollama instance running the `mistral` model.
- **`RagWorkflowService`**: Triggers graph runs, handles serialization, and wraps execution in a Resilience4j circuit breaker.

---

## 4. Agentic Workflow Orchestration (LangGraph4j)

DocMind leverages **LangGraph4j**'s stateful graph machine to construct the RAG loop. Nodes operate as independent step executors that communicate solely by reading and updating a shared state.

### 4.1 State Channels & Merging (`RagState`)

State is managed by specifying custom **Channels** in the schema. When a node returns a partial update map, LangGraph4j merges it using these strategies:

- **`Channels.base(Supplier)`**: Overwrites the value. Used for scalar values such as `query`, `answer`, `isValid`, `modelUsed`, and `startTimeMs`.
- **`Channels.appender(Supplier)`**: Appends updates into a collection instead of overwriting them. Used for `retrievedDocs`. On retry, newly retrieved documents are appended to the existing context, preventing context loss.

```java
public static final Map<String, Channel<?>> SCHEMA = Map.of(
    "query",               Channels.<String>base(() -> ""),
    "sourceFilter",        Channels.<String>base(() -> null),
    "retrievedDocs",       Channels.<Document>appender(ArrayList::new),
    "answer",              Channels.<String>base(() -> null),
    "isValid",             Channels.<Boolean>base(() -> false),
    "retryCount",          Channels.<Integer>base(() -> 0),
    "validationReasoning", Channels.<String>base(() -> null),
    "modelUsed",           Channels.<String>base(() -> null),
    "startTimeMs",         Channels.<Long>base(System::currentTimeMillis)
);
```

### 4.2 Graph Topology Configuration (`RagGraphConfig`)

The graph structure is compiled as a Spring `@Bean` at application startup. This checks the workflow structure for cycles, validates terminal nodes, and prepares the execution engine once.

```java
StateGraph<RagState> graph = new StateGraph<>(RagState.SCHEMA, RagState::new)
    // Register executable node actions
    .addNode(NODE_RETRIEVE, node_async(retrievalNode::execute))
    .addNode(NODE_GENERATE, node_async(generationNode::execute))
    .addNode(NODE_VALIDATE, node_async(validationNode::execute))

    // Define workflow transitions
    .addEdge(START,         NODE_RETRIEVE)
    .addEdge(NODE_RETRIEVE, NODE_GENERATE)
    .addEdge(NODE_GENERATE, NODE_VALIDATE)

    // Route dynamically based on validation outcomes
    .addConditionalEdges(
        NODE_VALIDATE,
        edge_async(this::routeAfterValidation),
        Map.of(
            ROUTE_RETRY, NODE_RETRIEVE,  // Loop back if validation fails
            ROUTE_END,   END             // Finish if valid or max retries reached
        )
    );
```

### 4.3 Node Implementations

#### 1. Retrieval Node (`RetrievalNode.java`)
Executes similarity queries against ChromaDB. 
- **Query Modification on Retry**: If `retryCount > 0`, the query is prefixed with `"Provide detailed information about: "` to broaden the embedding search.
- **Filtering**: Supports metadata filtering (e.g., limiting search scope to `sourceTag == 'product-manual'`).

#### 2. Generation Node (`GenerationNode.java`)
Calls OpenAI's `gpt-4o` using Spring AI's fluent `ChatClient` abstraction.
- Formulates a system prompt providing the retrieved document chunks as ground-truth context.
- Fallback: If no document chunks are retrieved, it returns a polite, pre-defined fallback indicating that no context was found, avoiding hallucinations.

#### 3. Validation Node (`ValidationNode.java`)
Executes an LLM-as-a-Judge step using Ollama (`mistral`).
- Evaluates the generated answer against the retrieved document chunks.
- Output: Returns a structured evaluation JSON containing `isValid` (true/false) and `reasoning`.
- Increments the `retryCount` state variable.

---

## 5. Document Ingestion Pipeline

The document ingestion pipeline parses incoming binaries, segments them into chunks, embeds them, and uploads them to the vector store.

```
File Upload → [Apache Tika Parser] → Raw Text String → [Chunking Strategy] → List<Chunk>
                                                                                 │
   ChromaDB ← [Chroma Vector Store] ← List<Document> (w/ Embeddings) ← [Embedding Model]
```

### 5.1 Document Parsing
**Apache Tika** is utilized in `DocumentIngestionService` to extract text contents from multiple file extensions (PDF, DOCX, TXT, HTML, etc.) without writing file-specific parsers.

### 5.2 Chunking Strategies
DocMind implements two swappable chunking strategies to support the Open/Closed Principle:

1. **`FixedSizeChunkingStrategy`**:
   - Splits text into fixed character lengths (default: `1000`) with a configurable `overlap` (default: `128`) to maintain contextual continuity.
2. **`ParagraphChunkingStrategy`**:
   - Splits text by paragraph delimiters (`\n\n`) and dynamically groups consecutive paragraphs up to `chunkSize` bounds. This preserves natural semantic structure, as paragraphs express complete thoughts. Large paragraphs are split as a fallback.

**Configuration**:
Configure the desired strategy in `application.yml` via the `docmind.ingestion.chunker-type` property (`fixed-size` or `paragraph`).
- **Chunk Size & Overlap**: Configured by token/character counts (default: `1000` / `128`).
- **Metadata**: Each chunk retains metadata, including the filename, ingestion timestamp, and an optional `sourceTag` for scoped retrieval.

---

## 6. Resilience & Observability

### 6.1 Resilience4j Circuit Breaker
LLM calls are subject to network failures, rate limiting, and downtime. DocMind wraps the LangGraph4j engine execution in a Resilience4j circuit breaker (`RagWorkflowService.java`):

- **Circuit Open State**: If error rates exceed `50%` over a sliding window, the circuit opens, immediately failing fast and returning a structured `RagWorkflowException`.
- **Failover / Fallback**: Ensures API clients get immediate feedback rather than hung sockets during LLM service outages.

### 6.2 Micrometer Observability (Prometheus & Grafana)
We expose operational metrics to **Micrometer**, which are scraped by **Prometheus**:

- **Custom Timers**: Measures the duration of the entire RAG pipeline and individual graph node executions.
- **Counters**: Tracks ingestion files, LLM request counts, retry loop frequencies, and validation failures.
- **Endpoint**: Available via Spring Boot Actuator at `/actuator/prometheus`.

---

## 7. API Reference & Usage Guides

### 7.1 Ingestion API
Allows uploading document files to the knowledge base.

*   **Endpoint**: `POST /api/v1/documents`
*   **Content-Type**: `multipart/form-data`
*   **Parameters**:
    - `file` (Multipart file, Required): The document file (PDF, TXT, etc.).
    - `sourceTag` (String, Optional): A metadata tag to label the source.

#### Curl Example:
```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@/Users/user/Documents/spring-ai-guide.pdf" \
  -F "sourceTag=reference-manual"
```

### 7.2 Deletion API
Allows deleting previously uploaded documents and clearing their vector chunks from ChromaDB.

*   **Endpoint**: `DELETE /api/v1/documents/{documentId}`
*   **Response**: `204 No Content` on success.

#### Curl Example:
```bash
curl -X DELETE http://localhost:8080/api/v1/documents/a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d
```

### 7.3 Query API
Queries the RAG pipeline.

*   **Endpoint**: `POST /api/v1/query`
*   **Content-Type**: `application/json`
*   **Request Body**:
    ```json
    {
      "query": "What is the primary feature of Spring AI?",
      "sourceFilter": "reference-manual"
    }
    ```

#### Response Body:
```json
{
  "query": "What is the primary feature of Spring AI?",
  "answer": "The primary feature of Spring AI is its portable API abstraction that simplifies integration with multiple LLM providers and vector stores.",
  "sources": [
    {
      "content": "Spring AI provides generic abstractions for ChatClient, VectorStore...",
      "metadata": {
        "source": "spring-ai-guide.pdf",
        "sourceTag": "reference-manual"
      }
    }
  ],
  "retryCount": 0,
  "elapsedTimeMs": 245,
  "modelUsed": "gpt-4o",
  "success": true
}
```

---

## 8. Local Development & Deployment

### 8.1 Local Execution Prerequisites
1. **Docker**: Running on the local machine.
2. **Java 21 JDK**: Configured in your system environment path.

### 8.2 Starting the Environment Stack
Run the following commands in the project root:

```bash
# 1. Configure API keys (Copy template and add your OpenAI key)
cp .env.example .env
echo "OPENAI_API_KEY=sk-proj-yourOpenAiKey..." >> .env

# 2. Start ChromaDB, Ollama, Prometheus, and Grafana
docker compose up -d

# 3. Pull the Mistral model inside the Ollama container (one-time setup)
docker exec -it docmind-ollama ollama pull mistral
```

### 8.3 Building and Packaging
Build the Maven Reactor project:

```bash
# Compile and package all multi-module jars
/tmp/apache-maven-3.9.9/bin/mvn clean package

# Start the Spring Boot application (docmind-api module)
/tmp/apache-maven-3.9.9/bin/mvn spring-boot:run -pl docmind-api
```

Once running, the following interfaces are available:
- **Swagger UI**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **Actuator Health**: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
- **Prometheus Metrics**: [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus)
- **Grafana Dashboard**: [http://localhost:3000](http://localhost:3000) (User: `admin`, Password: `admin`)
