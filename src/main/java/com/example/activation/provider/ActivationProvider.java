package com.example.activation.provider;

public interface ActivationProvider {

    Outcome activate(ActivationCommand command);

    enum Outcome {
        COMPLETED,
        REJECTED,
        RETRYABLE_FAILURE
    }
}
