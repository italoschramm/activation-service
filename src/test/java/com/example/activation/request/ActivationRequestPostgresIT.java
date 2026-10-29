package com.example.activation.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "activation.processing.cron=-")
@Testcontainers
class ActivationRequestPostgresIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("activation")
            .withUsername("activation")
            .withPassword("activation");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActivationRequestRepository repository;

    @BeforeEach
    void clearDatabase() {
        repository.deleteAll();
    }

    @Test
    void concurrentIdenticalRequestsCreateExactlyOneRow() throws Exception {
        String body = """
                {"externalId":"EXT-PG-SAME","customerId":"CUST-10001","productCode":"INTERNET-500"}
                """;
        List<MockHttpServletResponse> responses = sendConcurrently(List.of(
                body, body, body, body, body, body, body, body));

        assertThat(responses.stream().map(MockHttpServletResponse::getStatus).toList())
                .containsExactlyInAnyOrder(201, 200, 200, 200, 200, 200, 200, 200);
        HashSet<String> ids = new HashSet<>();
        for (MockHttpServletResponse response : responses) {
            ids.add(objectMapper.readTree(response.getContentAsString()).get("id").asText());
        }
        assertThat(ids).hasSize(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void concurrentConflictingRequestsKeepTheFirstContent() throws Exception {
        String productA = """
                {"externalId":"EXT-PG-CONFLICT","customerId":"CUST-10001","productCode":"INTERNET-500"}
                """;
        String productB = """
                {"externalId":"EXT-PG-CONFLICT","customerId":"CUST-10001","productCode":"INTERNET-300"}
                """;
        List<MockHttpServletResponse> responses = sendConcurrently(List.of(
                productA, productB, productA, productB, productA, productB));

        assertThat(responses.stream().map(MockHttpServletResponse::getStatus).toList())
                .containsExactlyInAnyOrder(201, 200, 200, 409, 409, 409);
        assertThat(repository.count()).isEqualTo(1);
        String storedProduct = repository.findByExternalId("EXT-PG-CONFLICT")
                .orElseThrow().getProductCode();
        assertThat(storedProduct).isIn("INTERNET-500", "INTERNET-300");
    }

    private List<MockHttpServletResponse> sendConcurrently(List<String> bodies) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(bodies.size());
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (String body : bodies) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/api/v1/requests")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn().getResponse();
                }));
            }
            start.countDown();
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }
}
