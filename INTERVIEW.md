# INTERVIEW.md — System Design & Engineering Interview Prep

> Complete preparation guide for technical interviews about DocMind and AI systems.

---

## 🏗️ System Design Questions

### Q1: How would you scale DocMind to 10,000 RPS?

**Current architecture** (single instance):
- Bottleneck 1: OpenAI API rate limits (~3500 RPM on Tier 1, ~10,000+ on Tier 5)
- Bottleneck 2: ChromaDB single instance
- Bottleneck 3: Thread pool size (default 200 threads in Spring MVC)

**Scaling strategy**:

```
Tier 1 — Horizontal Scaling (0→1K RPS):
  - Deploy 3-5 Spring Boot instances behind a load balancer (AWS ALB)
  - CompiledGraph is stateless → safe to horizontally scale
  - Switch to reactive Spring WebFlux (non-blocking) to handle more concurrent requests
  - Replace InMemoryCheckpointSaver with Redis (shared state across instances)

Tier 2 — Caching (1K→5K RPS):
  - Cache popular query embeddings in Redis (TTL = 1 hour)
  - Cache query→answer pairs for identical queries (exact match cache)
    - 60-70% cache hit rate typical in enterprise knowledge bases
  - Cache ChromaDB search results by query vector fingerprint

Tier 3 — Infrastructure (5K→10K RPS):
  - ChromaDB: Switch to Pinecone (managed, auto-scales, replicated)
  - LLM: OpenAI Batch API for non-real-time queries (50% cost reduction)
  - Add CDN for the API layer (Cloudflare) if queries are geographically distributed
  - Implement request queuing (AWS SQS) for ingestion (bursty workload)
  - Use async ingestion: POST /documents returns immediately, processing happens in background
```

**Back-of-envelope numbers**:
- 10,000 RPS × 60s = 600,000 RPM
- OpenAI Tier 5: ~10,000 RPM
- Need: ~60 parallel OpenAI connections or a mix of OpenAI + other providers
- Solution: LLM routing (OpenAI for premium users, Groq/Claude for standard tier)

---

### Q2: How would you add streaming responses to the API?

```java
// Replace synchronous controller with SSE streaming
@GetMapping(value = "/api/v1/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> streamQuery(@RequestParam String query) {
    return ragWorkflowService.streamQuery(query)
        .map(token -> ServerSentEvent.<String>builder()
            .data(token)
            .event("token")
            .build())
        .concatWith(Flux.just(ServerSentEvent.<String>builder()
            .event("done")
            .data("[DONE]")
            .build()));
}

// In the service — use Spring AI's streaming ChatClient
public Flux<String> streamQuery(String query) {
    return openAiChatClient.prompt()
        .user(query)
        .stream()
        .content();  // Returns Flux<String> of tokens
}
```

**Challenge**: The validation step (Ollama) cannot stream — it needs the full answer to validate. Solution: stream the answer optimistically, then send a `"validated": false` event if validation fails, prompting a retry on the client side.

---

### Q3: How would you add multi-tenancy (each customer gets their own knowledge base)?

**Current**: Single ChromaDB collection `docmind-kb` shared by all users.

**Multi-tenant approach**:
```java
// Strategy 1: Metadata filtering (simple, good for <10K tenants)
vectorStore.similaritySearch(
    SearchRequest.query(userQuery)
        .withFilterExpression(new FilterExpressionBuilder()
            .eq("tenantId", currentUser.getTenantId())
            .build())
);

// Strategy 2: Separate collections per tenant (isolation, but more overhead)
// Requires dynamic VectorStore creation or a VectorStore factory
```

**Trade-offs**:
| Approach | Isolation | Complexity | Cost |
|----------|-----------|-----------|------|
| Metadata filter | Shared DB, logical isolation | Low | Low |
| Separate collections | Strong isolation | Medium | Medium |
| Separate ChromaDB instances | Complete isolation | High | High |

---

### Q4: How would you add authentication to DocMind?

```java
// Add Spring Security dependency
// application.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://your-auth-server.com

// SecurityConfig.java
@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health").permitAll()
            .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
            .requestMatchers("POST /api/v1/documents").hasRole("ADMIN")
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .build();
}
```

---

## 💻 Code Challenge Questions

### Challenge 1: Implement a custom LangGraph4j node that calls an external REST API

```java
@Component
public class ExternalApiNode {
    
    private final RestClient restClient;
    
    public ExternalApiNode(RestClient.Builder builder) {
        this.restClient = builder
            .baseUrl("https://api.example.com")
            .build();
    }
    
    public Map<String, Object> execute(RagState state) {
        // Call external API with the current query
        ApiResult result = restClient.get()
            .uri("/search?q={query}", state.query())
            .retrieve()
            .body(ApiResult.class);
        
        // Return partial state update (new documents to add to retrieved list)
        List<Document> externalDocs = result.items().stream()
            .map(item -> new Document(item.content(), Map.of("source", "external-api")))
            .toList();
        
        return Map.of("retrievedDocs", externalDocs);
    }
}

// Wire it into the graph:
graph.addNode("external-search", node_async(externalApiNode::execute))
     .addEdge("retrieve", "external-search")       // ChromaDB → External API
     .addEdge("external-search", "generate");       // Both results → Generation
```

### Challenge 2: Add rate limiting per API key

```java
// Using Resilience4j RateLimiter
@Bean
RateLimiter apiKeyRateLimiter(RateLimiterRegistry registry) {
    return registry.rateLimiter("perApiKey", RateLimiterConfig.custom()
        .limitForPeriod(100)           // 100 calls per minute
        .limitRefreshPeriod(Duration.ofMinutes(1))
        .timeoutDuration(Duration.ofSeconds(2))
        .build());
}

// In the controller:
@PostMapping("/api/v1/query")
public ResponseEntity<QueryResponse> query(
        @RequestHeader("X-API-Key") String apiKey,
        @RequestBody QueryRequest request) {
    return RateLimiter.decorateSupplier(
        registry.rateLimiter("perApiKey-" + apiKey),
        () -> ResponseEntity.ok(ragWorkflowService.query(request))
    ).get();
}
```

---

## 🧠 Behavioral Questions

### Q: How did you debug a failing LangGraph4j workflow?

**Situation**: In testing, the validation node was occasionally marking valid answers as invalid, causing unnecessary retries and higher latency.

**Task**: Diagnose whether the issue was in the prompt, the Ollama model, or the JSON parsing logic.

**Action**:
1. Enabled `DEBUG` logging for `org.bsc.langgraph4j` to see every node transition and state delta.
2. Added structured logging in `ValidationNode` to log the raw Mistral response before parsing.
3. Discovered that Mistral occasionally prefixed its JSON response with "Sure, here is my evaluation:" — breaking our simple JSON parser.
4. Fixed by extracting JSON from the response using a regex to find the `{...}` block.

**Result**: Validation false-positive rate dropped from ~15% to <1%.

**Lesson**: Always log raw LLM responses in development. LLMs are non-deterministic — your parsing must be defensive.

---

### Q: How would you approach A/B testing two different prompts in production?

```java
// Feature flag-based prompt selection
@Service
public class PromptSelector {
    
    @Value("${docmind.prompts.variant:A}")
    private String promptVariant;
    
    public String getSystemPrompt() {
        return switch (promptVariant) {
            case "A" -> PROMPT_V1;  // Current production prompt
            case "B" -> PROMPT_V2;  // New prompt under test
            default -> PROMPT_V1;
        };
    }
}

// Metrics: tag all LLM calls with the prompt variant
meterRegistry.counter("rag.query.completed",
    "validated", String.valueOf(isValid),
    "prompt.variant", promptVariant
).increment();

// A/B split: deploy with DOCMIND_PROMPTS_VARIANT=B to 10% of pods
// Compare: validation rate, user satisfaction (thumbs up/down endpoint), latency
```

---

### Q: How would you handle LLM context window limits for very long documents?

**Problem**: GPT-4o has a 128K token context window. A 300-page PDF has ~200K tokens — exceeds the limit.

**Solutions** (in order of sophistication):

1. **Chunking (already done)**: Split into 1024-char chunks. Only relevant chunks go into the prompt. This is the RAG approach — never send the full document.

2. **Map-Reduce for summarization**: 
   - Map: Summarize each chunk independently (parallel LLM calls)
   - Reduce: Summarize all chunk summaries into a final summary
   
3. **Hierarchical RAG**: 
   - Level 1: Embed document-level summaries (fast, coarse)
   - Level 2: Embed chunk-level content (slow, fine-grained)
   - Query: First search summaries, then search within the top matching documents

4. **Reranking**: Retrieve top-50 chunks, use a lightweight cross-encoder model to rerank and keep only top-5 most relevant. Less hallucination, better precision.

---

## 📊 Metrics & Observability

### Key metrics to track in production:

| Metric | Type | Alert Threshold |
|--------|------|-----------------|
| `docmind.rag.query.duration` | Timer (p99) | > 10s |
| `docmind.rag.queries{validated=false}` | Counter | > 30% of total |
| `docmind.ingestion.errors` | Counter | Any non-zero spike |
| `gen_ai.client.token.usage` | Counter | Cost monitoring |
| `resilience4j.circuitbreaker.state` | Gauge | State = OPEN |
| `docmind.rag.circuit-breaker.open` | Counter | Any occurrence |

### Grafana Dashboard panels:
1. Query rate (RPS) — `rate(docmind_rag_queries_total[5m])`
2. Validation success rate — `validated=true / total`
3. P99 latency — `histogram_quantile(0.99, docmind_rag_query_duration_seconds)`
4. OpenAI token usage — `gen_ai_client_token_usage_total`
5. Circuit breaker state — `resilience4j_circuitbreaker_state`

---

## 🚀 Application & Scenario-Based Questions

### Q1: How do you handle unsupported or corrupted file types during ingestion?
**Scenario**: Users upload `.exe` files or corrupted PDFs, crashing the ingestion worker.
**Answer**: Implement a multi-layered validation strategy. First, validate the MIME type and file extension at the API Gateway or controller level. Use Apache Tika to reliably detect the actual file type regardless of the extension. For corrupted files, wrap the `TikaDocumentReader` execution in a `try-catch` block and return a structured `400 Bad Request` or `422 Unprocessable Entity` response instead of throwing a raw 500 error, allowing the frontend to show a user-friendly error message.

### Q2: What is your strategy for upgrading Spring AI when breaking API changes occur?
**Scenario**: You need to upgrade Spring AI from M1 to a GA release, which renames `ChatClient` methods.
**Answer**: Isolate the Spring AI dependencies using the Adapter or Facade design pattern. Instead of exposing `ChatClient` directly to business logic, create a `LlmService` interface. When upgrading, you only need to modify the implementation class (e.g., `SpringAiOpenAiAdapter`) rather than hunting down LLM calls across the entire codebase. Also, ensure extensive unit tests cover the adapter using Mockito.

### Q3: How would you resolve a ChromaDB Out of Memory (OOM) issue?
**Scenario**: The vector database container crashes under heavy load with large document sets.
**Answer**: ChromaDB stores vectors in memory for fast HNSW index searching. To resolve OOM:
1. Increase the container memory limit in `docker-compose.yml`.
2. Move to a managed vector database (like Pinecone or Weaviate) if the dataset outgrows single-node limits.
3. Optimize ingestion by reducing embedding dimensions (if supported) or deleting outdated document collections periodically.

### Q4: Users report hallucinations despite the Mistral validation node. What are your next steps?
**Scenario**: The fact-checking node is failing to catch subtle inaccuracies.
**Answer**: 
1. **Prompt Tuning**: The validation prompt might be too lenient. Modify it to demand strict citations from the provided context.
2. **Temperature Adjustment**: Ensure the Mistral model's temperature is set to `0.0` to minimize its own hallucinations during validation.
3. **Model Swap**: If Mistral 7B struggles with complex reasoning, upgrade the local model to Llama 3 8B or Mistral 8x7B (if hardware permits), or fall back to a cloud model for validation.

### Q5: How would you implement semantic caching to reduce OpenAI costs?
**Scenario**: Multiple users ask the same or semantically identical questions.
**Answer**: Implement a semantic cache using Redis and a vector store. Before querying GPT-4o, embed the user's query and do a similarity search against a `cache` collection in ChromaDB. If a match is found with a similarity score > 0.98, return the cached answer immediately. This skips the generation phase entirely, drastically reducing API costs and latency.

### Q6: How would you add a new LLM provider (e.g., Anthropic Claude) alongside OpenAI?
**Scenario**: You want to route complex queries to Claude 3.5 Sonnet and simple ones to GPT-4o mini.
**Answer**: Add the `spring-ai-anthropic-spring-boot-starter` dependency. Inject multiple `ChatModel` beans using `@Qualifier`. Create a router service that inspects the query complexity or checks the user's subscription tier, then dynamically selects either the OpenAI or Anthropic bean to handle the request.

### Q7: How do you manage API Gateway timeouts for long-running RAG queries?
**Scenario**: Some complex document synthesis takes 15 seconds, but the Spring Cloud Gateway times out after 10 seconds.
**Answer**: 
1. Increase the `response-timeout` configuration in the Gateway routes.
2. Better yet, shift from synchronous REST to asynchronous processing. Return a `202 Accepted` with a `jobId`, and have the Angular frontend poll a `/status/{jobId}` endpoint or listen via Server-Sent Events (SSE) or WebSockets for the completion event.

### Q8: How would you migrate LangGraph4j state from memory to Redis for horizontal scaling?
**Scenario**: You deploy 3 instances of `docmind-api`, breaking the `InMemoryCheckpointSaver`.
**Answer**: Implement a custom `CheckpointSaver` interface in LangGraph4j that serializes the `RagState` object to JSON and stores it in Redis using Spring Data Redis. Use the graph's `thread_id` or `conversation_id` as the Redis key. This allows any of the 3 API instances to pick up the next step in the graph workflow seamlessly.

### Q9: How can you show real-time ingestion progress in the Angular UI?
**Scenario**: Uploading a 100MB PDF takes time; users think the app is frozen.
**Answer**: Implement WebSockets (using Spring WebSocket/STOMP) or SSE. When the file is uploaded, the API responds with a transaction ID. The backend then publishes progress events (`PARSING`, `SPLITTING`, `EMBEDDING`, `COMPLETED`) to a message broker or directly over the WebSocket channel. The Angular UI subscribes to this channel and updates a progress bar component.

### Q10: What happens if you change the embedding model (e.g., from ada-002 to text-embedding-3-small)?
**Scenario**: You want to upgrade to a cheaper, better embedding model.
**Answer**: Vector spaces are incompatible across different models. You cannot compare an `ada-002` embedding with a `text-embedding-3` embedding. You must perform a data migration: read all existing text chunks from ChromaDB, re-embed them using the new model, and store them in a new ChromaDB collection. Once complete, switch the API to point to the new collection and deprecate the old one.

### Q11: How do you securely handle PII in ingested documents?
**Scenario**: Users upload HR documents containing Social Security Numbers.
**Answer**: Introduce a Data Loss Prevention (DLP) or anonymization node *before* the text chunks are embedded and sent to the LLM. You can use regex patterns, Presidio (via a Python microservice), or a local fast LLM/NER model to mask entities (e.g., replacing names with `[PERSON]`). Store the mapping securely if deanonymization is required later.

### Q12: What is your fallback strategy if the OpenAI API goes down entirely?
**Scenario**: OpenAI experiences a total regional outage.
**Answer**: Use Spring Retry or Resilience4j Circuit Breaker on the `ChatClient`. If the circuit opens due to 5xx errors from OpenAI, trigger a fallback method. The fallback should route the request to the local Ollama instance (Mistral) to handle the generation phase. While the quality might be slightly lower, the application remains highly available.

### Q13: How do you deal with text chunking cutting off a sentence midway?
**Scenario**: A crucial semantic detail is split across two separate chunks, causing retrieval failure.
**Answer**: Use an overlapping chunking strategy. When configuring `TokenTextSplitter` in Spring AI, set a `chunkSize` (e.g., 1000 tokens) and an `overlapSize` (e.g., 200 tokens). The overlap ensures that sentences or paragraphs spanning a boundary are fully captured in at least one chunk, preserving context.

### Q14: How do you implement conversational memory (chat history) in LangGraph4j?
**Scenario**: The user says "Summarize it", referring to the previous answer.
**Answer**: Add a `chatHistory` list (e.g., `List<Message>`) to the `RagState` object. Before the `Generate Node` executes, prepend the `chatHistory` to the current `userQuery`. Update the `chatHistory` at the `END` node with the new query and generated answer. Ensure this state is persisted in Redis or a database using a `conversationId`.

### Q15: How do you unit test a LangGraph4j workflow?
**Scenario**: You need to prove the graph logic works without calling expensive LLM APIs.
**Answer**: Mock the external dependencies (ChromaDB, OpenAI) using Mockito. Create a `CompiledGraph` in the test setup. Pass a dummy `RagState` into the graph and use `.invoke()` or step through the graph manually. Assert that the graph traverses the correct edges (e.g., simulating a `false` from the validation node should correctly route back to the retrieval node).

### Q16: How do you handle partial failures during large document ingestion?
**Scenario**: A 500-page document fails at page 450 during embedding due to a network blip.
**Answer**: Implement transactional behavior for document ingestion. Use a staging table or an event-driven saga pattern. Process embeddings in batches (e.g., 50 chunks at a time). Keep track of processed batches in a database. If a failure occurs, the system can resume from the last successful batch rather than starting over, preventing duplicate embeddings and wasted API costs.

### Q17: How would you optimize the Angular application's bundle size?
**Scenario**: The initial load time of the UI is slow because the main bundle is 5MB.
**Answer**: 
1. Use Angular's lazy loading for routes (e.g., load the Chat feature module only when the user navigates to it).
2. Ensure standalone components are used effectively to tree-shake unused code.
3. Defer loading of heavy UI libraries (like Markdown parsers or charting libraries) using the `@defer` block introduced in Angular 17.

### Q18: How do you handle Eureka split-brain or registration issues in production?
**Scenario**: The API Gateway cannot find the `docmind-api` service.
**Answer**: 
1. Run a cluster of Eureka servers (at least 3 nodes) for high availability, configuring them to register with each other.
2. In the microservices, configure `eureka.client.registry-fetch-interval-seconds` to a lower value for faster discovery.
3. Implement client-side load balancing retries in the Gateway so that if it hits a stale instance, it automatically retries the next healthy instance.

### Q19: How would you improve search accuracy beyond simple vector similarity?
**Scenario**: Users search for exact part numbers, but vector search returns semantically similar but incorrect parts.
**Answer**: Implement **Hybrid Search**. Combine dense vector search (ChromaDB) with sparse keyword search (BM25 algorithms, typically via Elasticsearch or PostgreSQL with pgvector). Retrieve top results from both, and use a Reciprocal Rank Fusion (RRF) algorithm to combine the scores. Finally, pass the combined list to the LLM.

### Q20: How do you manage system prompts as code versus configuration?
**Scenario**: Non-technical domain experts want to tweak the RAG prompt without waiting for a developer to deploy code.
**Answer**: Move the prompts out of hardcoded Java strings. Store them in a database table (`prompts` with `version` and `isActive` flags) or a centralized configuration server (Spring Cloud Config). Create an admin UI in Angular where authorized users can edit the prompt. The `docmind-api` can then fetch and cache the active prompt dynamically, refreshing it via a `@RefreshScope` when changed.
