package com.example.activation.request;

import com.example.activation.provider.ActivationProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivationRequestApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActivationRequestRepository repository;

    @Autowired
    private ActivationProcessor processor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ActivationProvider provider;

    @BeforeEach
    void clearDatabase() {
        repository.deleteAll();
    }

    @Test
    void createsAndFetchesRequest() throws Exception {
        String body = """
                {
                  "externalId": "EXT-12345",
                  "customerId": "CUST-10001",
                  "productCode": "INTERNET-500"
                }
                """;

        String responseBody = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalId").value("EXT-12345"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(header().string("Location", startsWith("/api/v1/requests/")))
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(responseBody).get("id").asText();
        assertThat(repository.count()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/requests/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.customerId").value("CUST-10001"))
                .andExpect(jsonPath("$.productCode").value("INTERNET-500"));
    }

    @Test
    void rejectsMissingRequiredField() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"EXT-12345","productCode":"INTERNET-500"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.errors[0].field").value("customerId"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        assertThat(repository.count()).isZero();
    }

    @Test
    void returnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Solicitação não encontrada"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void returnsExistingRequestForIdenticalRetry() throws Exception {
        String body = """
                {"externalId":"EXT-RETRY","customerId":"CUST-10001","productCode":"INTERNET-500"}
                """;

        String first = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String retry = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(retry).get("id").asText())
                .isEqualTo(objectMapper.readTree(first).get("id").asText());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void returnsConflictWhenExternalIdHasDifferentContent() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"EXT-CONFLICT","customerId":"CUST-10001","productCode":"INTERNET-500"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"EXT-CONFLICT","customerId":"CUST-10001","productCode":"INTERNET-300"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "externalId 'EXT-CONFLICT' já existe com customerId ou productCode diferente"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalRequestsCreateOnlyOneRecord() throws Exception {
        int requestCount = 6;
        String body = """
                {"externalId":"EXT-CONCURRENT","customerId":"CUST-10001","productCode":"INTERNET-500"}
                """;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/api/v1/requests")
                                    .contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn().getResponse();
                }));
            }

            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            HashSet<String> ids = new HashSet<>();
            for (Future<MockHttpServletResponse> future : futures) {
                MockHttpServletResponse response = future.get();
                statuses.add(response.getStatus());
                ids.add(objectMapper.readTree(response.getContentAsString()).get("id").asText());
            }

            assertThat(statuses).containsExactlyInAnyOrder(201, 200, 200, 200, 200, 200);
            assertThat(ids).hasSize(1);
            assertThat(repository.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void marksRequestCompletedAfterProviderSuccess() throws Exception {
        when(provider.activate(any())).thenReturn(ActivationProvider.Outcome.COMPLETED);
        String id = createRequest("EXT-SUCCESS", "INTERNET-500");

        processor.processPending();

        mockMvc.perform(get("/api/v1/requests/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        verify(provider).activate(org.mockito.ArgumentMatchers.argThat(
                command -> command.externalId().equals("EXT-SUCCESS")
                        && command.productCode().equals("INTERNET-500")));
    }

    @Test
    void marksRequestFailedAfterProviderRejectsIt() throws Exception {
        when(provider.activate(any())).thenReturn(ActivationProvider.Outcome.REJECTED);
        String id = createRequest("EXT-REJECTED", "SIMULATE-400");

        processor.processPending();

        mockMvc.perform(get("/api/v1/requests/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(repository.findById(UUID.fromString(id)).orElseThrow().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void schedulesRetryAfterTemporaryProviderFailure() throws Exception {
        when(provider.activate(any())).thenReturn(ActivationProvider.Outcome.RETRYABLE_FAILURE);
        String id = createRequest("EXT-RETRY-PROVIDER", "SIMULATE-500");

        processor.processPending();

        ActivationRequest request = repository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttemptCount()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isAfter(java.time.Instant.now());
    }

    @Test
    void doesNotSendFourthAttemptAfterCrashOnLastAttempt() throws Exception {
        String id = createRequest("EXT-EXHAUSTED", "INTERNET-500");
        jdbcTemplate.update("""
                        update activation_requests
                        set status = 'PROCESSING', attempt_count = 3, updated_at = ?
                        where id = ?
                        """,
                Timestamp.from(Instant.now().minusSeconds(31)), UUID.fromString(id));

        processor.processPending();

        mockMvc.perform(get("/api/v1/requests/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(repository.findById(UUID.fromString(id)).orElseThrow().getAttemptCount()).isEqualTo(3);
        verifyNoInteractions(provider);
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("O corpo da requisição deve ser um JSON válido."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void rejectsInvalidRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/requests/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Parâmetro inválido"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private String createRequest(String externalId, String productCode) throws Exception {
        String response = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateActivationRequest(
                                externalId, "CUST-10001", productCode))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }
}
