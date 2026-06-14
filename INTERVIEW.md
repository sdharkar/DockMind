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
