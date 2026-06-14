# LEARNING.md — Spring AI + LangGraph4j Deep Dive

> A comprehensive breakdown of every concept used in DocMind.
> Use this as your study guide and interview cheat-sheet.

---

## 📚 Table of Contents

1. [Spring AI 1.0 Concepts](#1-spring-ai-10-concepts)
2. [LangGraph4j 1.8 Concepts](#2-langgraph4j-18-concepts)
3. [RAG Architecture Patterns](#3-rag-architecture-patterns)
4. [Production Patterns Used](#4-production-patterns-used)
5. [Common Interview Questions](#5-common-interview-questions)
6. [Troubleshooting Guide](#6-troubleshooting-guide)

---

## 1. Spring AI 1.0 Concepts

### 1.1 ChatClient — The Fluent AI API

`ChatClient` is Spring AI's primary abstraction for calling LLMs. It uses a fluent builder pattern:

```java
// Inject the builder (auto-configured by Spring AI starter)
@Bean
public ChatClient myClient(ChatClient.Builder builder) {
    return builder
        .defaultSystem("You are a helpful assistant.")
        .build();
}

// Call synchronously
String answer = chatClient.prompt()
    .user("What is RAG?")
    .call()
    .content();

// Stream response tokens
Flux<String> stream = chatClient.prompt()
    .user("Explain in detail...")
    .stream()
    .content();
```

**Key concept**: `ChatClient` wraps a `ChatModel`. Spring AI ships with:
- `OpenAiChatModel` — backed by OpenAI API
- `OllamaChatModel` — backed by local Ollama
- `AnthropicChatModel`, `VertexAiGeminiChatModel`, etc.

In DocMind, we inject TWO named `ChatClient` beans (via `@Qualifier`) to use different LLMs for different nodes.

---

### 1.2 EmbeddingModel — Text to Vectors

`EmbeddingModel` converts text to float vectors (embeddings). These vectors capture semantic meaning — similar texts have vectors with high cosine similarity.

```java
@Autowired EmbeddingModel embeddingModel;

// Convert text to vector
float[] vector = embeddingModel.embed("Spring AI makes LLM integration easy");
// vector.length == 1536 for text-embedding-3-small
```

**In DocMind**: We never call `EmbeddingModel` directly. Spring AI's `VectorStore.add()` calls it automatically for each document before storing. This is the Dependency Inversion Principle — the VectorStore depends on EmbeddingModel abstraction, not a concrete implementation.

---

### 1.3 VectorStore — Semantic Search Database

`VectorStore` abstracts vector databases. Spring AI 1.0 supports: ChromaDB, PGVector, Redis, Pinecone, Weaviate, and more.

```java
@Autowired VectorStore vectorStore;

// Store documents (auto-embeds each one)
vectorStore.add(List.of(
    new Document("Spring AI simplifies AI integration", Map.of("source", "docs"))
));

// Similarity search
List<Document> results = vectorStore.similaritySearch(
    SearchRequest.builder()
        .query("AI frameworks for Java")
        .topK(5)
        .similarityThreshold(0.7)
        .build()
);
```

**Trade-off — ChromaDB vs PGVector**:
- **ChromaDB**: Purpose-built, fast prototype, simple Docker setup. ✅ Used in DocMind.
- **PGVector**: Better if you already use PostgreSQL, ACID transactions, SQL queries over vectors.
- **Pinecone**: Fully managed, best scalability. High cost at scale.

---

### 1.4 Prompt Templates — Dynamic Inputs

`PromptTemplate` fills variable placeholders in prompts:

```java
PromptTemplate template = new PromptTemplate(
    "Summarize the following document in {language}: {content}"
);
Prompt prompt = template.create(Map.of(
    "language", "Spanish",
    "content", documentText
));
```

**In DocMind**: Node prompts are written inline using Java text blocks (`""" ... """`). This is intentional — complex multi-line prompts are more readable as text blocks than as external template files, and they benefit from IDE syntax highlighting.

---

### 1.5 Advisors — Composable AI Behaviors

Advisors are Spring AI 1.0's middleware layer for `ChatClient`. They intercept calls before/after the LLM:

```java
ChatClient client = builder
    .defaultAdvisors(
        new MessageChatMemoryAdvisor(chatMemory),    // Injects conversation history
        new QuestionAnswerAdvisor(vectorStore),       // Basic RAG advisor
        new ToolCallingAdvisor()                      // Tool execution loop
    )
    .build();
```

**Why DocMind doesn't use `QuestionAnswerAdvisor` for RAG**:
`QuestionAnswerAdvisor` is fine for simple RAG. DocMind uses a LangGraph4j pipeline instead because:
- We need the **retry loop** (re-retrieve if validation fails)
- We need **multi-LLM** (GPT-4o for generation, Mistral for validation)
- LangGraph4j gives us **explicit state**, **observability**, and **testability** per node.

---

## 2. LangGraph4j 1.8 Concepts

### 2.1 AgentState — The Shared Blackboard

All LangGraph4j graphs share state via an `AgentState` subclass. Each field has a **Channel** that defines how updates are merged.

```java
public class RagState extends AgentState {
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
        "query",         Channels.<String>base(() -> ""),              // Overwrite/Last value
        "retrievedDocs", Channels.<Document>appender(ArrayList::new),  // Accumulate
        "answer",        Channels.<String>base(() -> null),            // Overwrite/Last value
        "isValid",       Channels.<Boolean>base(() -> false)           // Overwrite/Last value
    );
    
    public RagState(Map<String, Object> initData) { super(initData); }
}
```

**Channel strategies explained**:

| Strategy | Behavior | When to Use |
|----------|----------|-------------|
| `base(supplier)` | Last write wins (overwrite) | Scalar state: current query, current answer, validation flag |
| `appender(supplier)` | Appends to a collection | Lists that grow: message history, retrieved docs |

**Interview Q**: *"Why use `appender` for `retrievedDocs` instead of `base`?"*
→ Because on retry, we want to accumulate retrieved docs across attempts. The GenerationNode receives a richer context on the second attempt without losing the first retrieval results.

---

### 2.2 StateGraph — Defining the Workflow

```java
StateGraph<RagState> graph = new StateGraph<>(RagState.SCHEMA, RagState::new)
    .addNode("retrieve", node_async(retrievalNode::execute))
    .addNode("generate", node_async(generationNode::execute))
    .addNode("validate", node_async(validationNode::execute))
    .addEdge(START, "retrieve")
    .addEdge("retrieve", "generate")
    .addEdge("generate", "validate")
    .addConditionalEdges("validate",
        edge_async(state -> state.isValid() ? "end" : "retry"),
        Map.of("end", END, "retry", "retrieve")
    );
```

**Key LangGraph4j idioms**:
- `node_async(fn)` — wraps a synchronous `Function<State, Map>` as an async node
- `edge_async(fn)` — wraps a synchronous routing function as an async edge
- `START` and `END` are reserved node names (graph entry/exit points)
- Each node receives the full state, returns ONLY the fields it changes

---

### 2.3 CompiledGraph — Thread-Safe Execution

```java
CompiledGraph<RagState> compiled = graph.compile(
    CompileConfig.builder()
        .checkpointSaver(new InMemoryCheckpointSaver())
        .build()
);

// Invoke (synchronous — waits for final state)
Optional<RagState> finalState = compiled.invoke(Map.of("query", "What is Spring AI?"));

// Stream (yields state after each node execution — good for debugging)
compiled.stream(initialState).forEach(nodeOutput -> log.debug("Node output: {}", nodeOutput));
```

**Why compile is a `@Bean`**:
- Compilation is expensive (validates graph, detects cycles, resolves nodes).
- `CompiledGraph` is thread-safe — a singleton bean handles all concurrent requests.
- All 1000 concurrent users share ONE `CompiledGraph` instance safely.

---

### 2.4 Checkpointing — State Persistence

`InMemoryCheckpointSaver` saves state between node executions within the same JVM session. For the retry loop, this means:

1. `RetrievalNode` runs → state saved (checkpoint 1)
2. `GenerationNode` runs → state saved (checkpoint 2)
3. `ValidationNode` fails → routes back to `RetrievalNode`
4. `RetrievalNode` runs again with accumulated state from checkpoint

**Production upgrade**: Replace `InMemoryCheckpointSaver` with a Redis or PostgreSQL-backed saver for:
- Multi-instance deployments (state shared across pods)
- Long-running workflows that survive restarts
- Human-in-the-loop workflows (pause/resume days later)

---

## 3. RAG Architecture Patterns

### 3.1 DocMind's RAG Pipeline

```
INGESTION TIME:
  Document → [Tika Parse] → [Chunk: fixed-size, 1024 chars, 128 overlap]
           → [Embed: OpenAI text-embedding-3-small] → [Store: ChromaDB]

QUERY TIME:
  Query → [Embed query] → [ChromaDB similarity search, top-5]
        → [GPT-4o: generate answer from context]
        → [Mistral: validate answer is grounded]
        → [If valid: return] [If invalid: retry with broader query]
```

### 3.2 Chunking Strategies Compared

| Strategy | Pros | Cons |
|----------|------|------|
| Fixed-size (DocMind) | Simple, fast, predictable | Can split mid-sentence |
| Sentence-boundary | Preserves semantic units | Requires NLP (Stanford/spaCy) |
| Semantic chunking | Best quality, groups related content | 2× embedding calls, expensive |
| Spring AI `TokenTextSplitter` | Token-aware, fits LLM context exactly | ~4× slower than fixed-char |

### 3.3 LLM-as-Judge Pattern

DocMind uses Ollama Mistral to validate GPT-4o's output. This is the **LLM-as-Judge** pattern:
- Judge asks: "Is the answer supported by the source context? YES/NO + reason"
- Reduces hallucination by ~30-40% in RAG systems (based on industry research)
- Cost: 1 extra LLM call per query (offset by using a free local model)

---

## 4. Production Patterns Used

### 4.1 Circuit Breaker (Resilience4j)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      ragPipeline:
        failure-rate-threshold: 50    # Open after 50% failures
        wait-duration-in-open-state: 30s
```

**States**: CLOSED → OPEN → HALF_OPEN → CLOSED
- CLOSED: normal operation, all calls go through
- OPEN: circuit is open, fallback is immediately returned (no LLM calls)
- HALF_OPEN: 3 test calls to probe if service recovered

**Why this matters**: Without a circuit breaker, a single LLM API outage could cascade into thousands of failing requests, all waiting for timeouts, exhausting your thread pool.

### 4.2 Layered JAR + Multi-Stage Docker

```
# Why this matters for Docker build time:
Build time WITHOUT layered JARs: ~60s (full JAR changes every build)
Build time WITH layered JARs:     ~5s  (only 'application' layer changes)
```

### 4.3 @ConfigurationProperties vs @Value

```java
// ❌ Anti-pattern: scattered @Value annotations, no type safety
@Value("${docmind.ingestion.chunk-size:512}")
private int chunkSize;

// ✅ DocMind approach: grouped, type-safe, IDE-autocompleted
@ConfigurationProperties(prefix = "docmind.ingestion")
public record IngestionProperties(int chunkSize, int overlap, ...) {}
```

---

## 5. Common Interview Questions

### Q1: How does LangGraph4j manage state across retries?

**Answer**: LangGraph4j uses a Channel-based state model. Each state field has a Channel that defines its merge strategy. In DocMind, `retrievedDocs` uses `Channels.appender` — so on each retry, newly retrieved documents are appended to the existing list rather than replacing it. The `InMemoryCheckpointSaver` persists the state after each node, enabling the retry loop to access accumulated results from previous iterations. This is fundamentally different from a simple loop — it's a graph-theoretic approach where state is the first-class citizen.

### Q2: What's the difference between Spring AI's `QuestionAnswerAdvisor` and a LangGraph4j pipeline?

**Answer**: `QuestionAnswerAdvisor` is a simple, single-step RAG advisor: retrieve documents, inject into prompt, call LLM. It works great for simple use cases. LangGraph4j gives you a stateful, multi-step, observable pipeline with branching logic (conditional edges), retry loops, and multi-agent capabilities. For DocMind's validation requirement (two LLMs, retry on failure), LangGraph4j is the right tool.

### Q3: Why use OpenAI for generation but Ollama for validation?

**Answer**: GPT-4o provides the best generation quality for complex answers from context. Ollama Mistral is a free, local model that's excellent at simple binary judgments ("is this answer grounded?") — it doesn't need GPT-4o's reasoning depth for that task. This combination optimizes cost (one API call) and privacy (validation stays local).

### Q4: How would you implement conversation memory in DocMind?

**Answer**: Add a `conversationId` field to `QueryRequest`, use Spring AI's `MessageChatMemoryAdvisor` with JDBC-backed `ChatMemory`, and store the conversation ID in `RagState`. The advisor automatically injects previous messages into each new prompt. The conversation history could also be stored as a message list in the LangGraph4j state using `Channels.appender`.

### Q5: What happens if ChromaDB is down when a query comes in?

**Answer**: The `RetrievalNode` will throw an exception when `VectorStore.similaritySearch()` fails. This propagates to `RagWorkflowService`, which re-throws as `RagWorkflowException`. The `@CircuitBreaker` annotation catches this, increments the failure count, and eventually opens the circuit breaker after 50% failure rate. The fallback method returns a user-friendly "service temporarily unavailable" message. Prometheus metrics track circuit breaker state so ops gets alerted.

---

## 6. Troubleshooting Guide

### Issue: "Collection not found" error from ChromaDB

```
Cause: ChromaDB collection doesn't exist yet.
Fix: Set spring.ai.vectorstore.chroma.initialize-schema=true in application.yml
     Or manually create: curl -X POST http://localhost:8000/api/v1/collections -d '{"name":"docmind-kb"}'
```

### Issue: OpenAI API errors (401 Unauthorized)

```
Cause: OPENAI_API_KEY not set or expired.
Fix: export OPENAI_API_KEY=sk-...
     Verify: curl https://api.openai.com/v1/models -H "Authorization: Bearer $OPENAI_API_KEY"
```

### Issue: Ollama connection refused

```
Cause: Ollama not running or wrong port.
Fix: docker run -d -p 11434:11434 ollama/ollama
     docker exec <container> ollama pull mistral
     Verify: curl http://localhost:11434/api/tags
```

### Issue: LangGraph4j workflow hangs / deadlock

```
Cause 1: Node function throws unchecked exception silently.
Fix: Add try-catch in each node, log exceptions explicitly.

Cause 2: Conditional edge never returns END.
Fix: Add a maxRetries guard (DocMind already has this via retryCount >= maxRetries).

Debug: Enable DEBUG logging for org.bsc.langgraph4j to see node transitions.
```

### Issue: Out of memory during ingestion

```
Cause: Tika loads entire PDF into memory for large files.
Fix: Increase -Xmx or reduce max-file-size-mb in application.yml.
     For very large documents, consider streaming parsers.
```
