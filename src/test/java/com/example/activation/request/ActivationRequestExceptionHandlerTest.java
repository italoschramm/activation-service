package com.example.activation.request;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ActivationRequestExceptionHandlerTest {

    @Test
    void hidesInternalExceptionDetailsFromClient() throws Exception {
        ActivationRequestService service = mock(ActivationRequestService.class);
        UUID id = UUID.randomUUID();
        when(service.findById(id)).thenThrow(new IllegalStateException("senha-interna-secreta"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ActivationRequestController(service))
                .setControllerAdvice(new ActivationRequestExceptionHandler())
                .build();

        mockMvc.perform(get("/api/v1/requests/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Erro interno"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString("senha-interna-secreta"))));
    }
}
