package com.example.activation.provider;

public record ActivationCommand(String externalId, String customerId, String productCode) {
}
