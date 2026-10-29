package com.example.activation.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateActivationRequest(
        @NotBlank @Size(max = 100) String externalId,
        @NotBlank @Size(max = 100) String customerId,
        @NotBlank @Size(max = 100) String productCode
) {
}
