package com.docmind.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.annotation.EnableRetry;

import com.docmind.ingestion.config.IngestionProperties;

/**
 * DocMind Application Entry Point.
 *
 * @SpringBootApplication enables:
 *   - @ComponentScan: picks up all @Service, @Component, @Controller across all docmind.* packages
 *   - @EnableAutoConfiguration: wires Spring AI, ChromaDB, Actuator, etc. from classpath
 *   - @Configuration: this class itself is a config source
 *
 * @EnableConfigurationProperties: registers our type-safe @ConfigurationProperties beans.
 *
 * @EnableRetry: activates Spring Retry's @Retryable annotation support (AOP-based).
 *   We use this alongside Resilience4j — Spring Retry for simple method-level retries,
 *   Resilience4j for circuit breaking and more sophisticated retry policies.
 *
 * Startup validation:
 *   If OPENAI_API_KEY is not set, Spring AI auto-configuration will throw a clear
 *   BeanCreationException at startup — fail-fast is intentional.
 */
@SpringBootApplication(
    scanBasePackages = {
        "com.docmind.api",
        "com.docmind.core",
        "com.docmind.ingestion"
    }
)
@EnableConfigurationProperties(IngestionProperties.class)
@EnableRetry
public class DocMindApplication {

    public DocMindApplication(org.springframework.core.env.Environment env) {
        System.out.println(">>> CHROMADB DIAGNOSTIC: Dumping all spring.ai properties:");
        if (env instanceof org.springframework.core.env.AbstractEnvironment) {
            for (org.springframework.core.env.PropertySource<?> source : ((org.springframework.core.env.AbstractEnvironment) env).getPropertySources()) {
                if (source instanceof org.springframework.core.env.EnumerablePropertySource) {
                    for (String name : ((org.springframework.core.env.EnumerablePropertySource<?>) source).getPropertyNames()) {
                        if (name.startsWith("spring.ai")) {
                            System.out.println("  [" + source.getName() + "] " + name + "=" + source.getProperty(name));
                        }
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(DocMindApplication.class, args);
    }
}
