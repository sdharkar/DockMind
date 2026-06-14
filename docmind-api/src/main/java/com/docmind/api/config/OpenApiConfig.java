package com.docmind.api.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SpringDoc OpenAPI / Swagger UI configuration.
 *
 * Accessible at runtime:
 *   - Swagger UI:  http://localhost:8080/swagger-ui.html
 *   - OpenAPI JSON: http://localhost:8080/v3/api-docs
 *
 * Custom OpenAPI spec is registered as a Spring bean — this is the SpringDoc 2.x idiom.
 * The spec describes the API's purpose, contact info, and server URLs for different envs.
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:DocMind}")
    private String appName;

    @Bean
    public OpenAPI docMindOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("DocMind API")
                .description("""
                    **DocMind** — Multi-Model RAG Knowledge Assistant API.
                    
                    This API provides:
                    - 📄 **Document Ingestion**: Upload PDF/text files to build the knowledge base.
                    - 🤖 **AI-Powered Q&A**: Ask questions and get grounded, validated answers.
                    
                    **Architecture**:
                    - Spring Boot 4.0 + Spring AI 2.0 (OpenAI GPT-4o + Ollama Mistral)
                    - LangGraph4j 1.8 workflow orchestration (Retrieve → Generate → Validate)
                    - ChromaDB vector store
                    """)
                .version("1.0.0")
                .contact(new Contact()
                    .name("DocMind Team")
                    .email("docmind@example.com"))
                .license(new License()
                    .name("MIT")
                    .url("https://opensource.org/licenses/MIT")))
            .externalDocs(new ExternalDocumentation()
                .description("DocMind GitHub Repository & LEARNING.md")
                .url("https://github.com/your-org/docmind"))
            .servers(List.of(
                new Server().url("http://localhost:8080").description("Local Development"),
                new Server().url("http://localhost:8080").description("Docker Compose")
            ));
    }
}
