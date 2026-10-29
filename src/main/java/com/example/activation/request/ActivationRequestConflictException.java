package com.example.activation.request;

class ActivationRequestConflictException extends RuntimeException {

    ActivationRequestConflictException(String externalId) {
        super("externalId '" + externalId + "' já existe com customerId ou productCode diferente");
    }
}
