package com.example.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.worker.enabled=false",
        "app.security.allow-local-targets=true",
        "app.security.sources.demo.allowed-domains[0]=localhost",
        "app.crypto.secret=test-secret"
})
@AutoConfigureMockMvc
class NotificationApiTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    com.example.notification.core.NotificationWorker notificationWorker;

    @org.junit.jupiter.api.BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from delivery_attempt");
        jdbcTemplate.update("delete from notification_task");
    }

    @Test
    void duplicateSubmitWithSamePayloadReturnsExistingNotification() throws Exception {
        String body = requestBody("idem-1", "{\"event\":\"paid\"}");

        String first = mockMvc.perform(post("/notifications")
                        .header("X-Source-System", "demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String second = mockMvc.perform(post("/notifications")
                        .header("X-Source-System", "demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode firstJson = objectMapper.readTree(first);
        JsonNode secondJson = objectMapper.readTree(second);
        assertThat(secondJson.get("duplicate").asBoolean()).isTrue();
        assertThat(secondJson.get("notificationId").asText())
                .isEqualTo(firstJson.get("notificationId").asText());
    }

    @Test
    void duplicateSubmitWithDifferentPayloadReturnsConflict() throws Exception {
        mockMvc.perform(post("/notifications")
                        .header("X-Source-System", "demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("idem-2", "{\"event\":\"paid\"}")))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/notifications")
                        .header("X-Source-System", "demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("idem-2", "{\"event\":\"refunded\"}")))
                .andExpect(status().isConflict());
    }

    @Test
    void missingSourceSystemIsUnauthorized() throws Exception {
        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("idem-3", "{}")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void workerDeliversPendingNotificationAndWritesAttempt() throws Exception {
        AtomicReference<String> downstreamIdempotencyKey = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            downstreamIdempotencyKey.set(exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            String response = mockMvc.perform(post("/notifications")
                            .header("X-Source-System", "demo")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(
                                    "idem-worker",
                                    "http://localhost:" + port + "/hook",
                                    "{\"event\":\"paid\"}")))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            UUID notificationId = UUID.fromString(objectMapper
                    .readTree(response)
                    .get("notificationId")
                    .asText());

            assertThat(notificationWorker.dispatchDueTasks()).isEqualTo(1);

            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                String statusResponse = mockMvc.perform(get("/notifications/{id}", notificationId)
                                .header("X-Source-System", "demo"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
                JsonNode statusJson = objectMapper.readTree(statusResponse);
                assertThat(statusJson.get("status").asText()).isEqualTo("SUCCESS");
                assertThat(statusJson.get("attemptCount").asInt()).isEqualTo(1);
            });
            assertThat(downstreamIdempotencyKey.get()).isEqualTo("idem-worker");
        } finally {
            server.stop(0);
        }
    }

    private String requestBody(String idempotencyKey, String payload) throws Exception {
        return requestBody(idempotencyKey, "http://localhost:18080/hook", payload);
    }

    private String requestBody(String idempotencyKey, String targetUrl, String payload) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "idempotencyKey", idempotencyKey,
                "targetUrl", targetUrl,
                "method", "POST",
                "headers", Map.of("Content-Type", "application/json"),
                "body", payload
        ));
    }
}
