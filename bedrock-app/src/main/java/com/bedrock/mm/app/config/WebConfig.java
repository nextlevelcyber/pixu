package com.bedrock.mm.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import lombok.extern.slf4j.Slf4j;

/**
 * Web configuration for Bedrock application
 */
@Configuration
@Slf4j
public class WebConfig implements WebMvcConfigurer {
    
    private final BedrockConfig bedrockConfig;
    
    @Autowired
    public WebConfig(BedrockConfig bedrockConfig) {
        this.bedrockConfig = bedrockConfig;
    }
    
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        BedrockConfig.ServerConfig.Cors cors = bedrockConfig.getServer().getCors();
        
        if (cors.isEnabled()) {
            registry.addMapping("/**")
                .allowedOrigins(cors.getAllowedOrigins())
                .allowedMethods(cors.getAllowedMethods())
                .allowedHeaders(cors.getAllowedHeaders())
                .allowCredentials(cors.isAllowCredentials())
                .maxAge(cors.getMaxAge());
            
            log.info("CORS enabled with origins: {}", String.join(", ", cors.getAllowedOrigins()));
        } else {
            log.info("CORS is disabled");
        }
    }
}