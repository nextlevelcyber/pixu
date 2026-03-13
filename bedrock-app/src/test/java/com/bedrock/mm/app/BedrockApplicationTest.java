package com.bedrock.mm.app;

import com.bedrock.mm.app.config.BedrockConfig;
import com.bedrock.mm.app.runtime.lifecycle.ApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BedrockApplicationTest {
    
    @LocalServerPort
    private int port;
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Autowired
    private BedrockConfig config;
    
    @Autowired
    private ApplicationService applicationService;
    
    @Test
    void contextLoads() {
        assertNotNull(config);
        assertNotNull(applicationService);
    }
    
    @Test
    void testApplicationConfiguration() {
        assertEquals("test", config.getEnvironment());
        assertEquals(BedrockConfig.Mode.SIMULATION, config.getMode());
        assertFalse(config.isMonitorEnabled()); // Monitor is explicitly disabled in test config
        assertTrue(config.isMarketDataEnabled()); // SIMULATION mode enables market data
        assertTrue(config.isStrategyEnabled()); // SIMULATION mode enables strategy
        assertTrue(config.isAdapterEnabled()); // SIMULATION mode enables adapter
    }
    
    @Test
    void testHealthEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "http://localhost:" + port + "/api/api/v1/health", String.class);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
    
    @Test
    void testStatusEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "http://localhost:" + port + "/api/api/v1/status", String.class);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
    
    @Test
    void testApplicationService() {
        ApplicationService.ApplicationStatus status = applicationService.getStatus();
        
        assertNotNull(status);
        assertEquals("Bedrock Market Making System", status.getName());
        assertEquals("test", status.getEnvironment());
        assertEquals(BedrockConfig.Mode.SIMULATION, status.getMode());
    }
}
