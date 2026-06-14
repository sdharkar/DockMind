package com.docmind.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class DocMindEurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocMindEurekaServerApplication.class, args);
    }
}
