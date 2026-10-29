package com.example.activation.request;

import java.util.UUID;

class ActivationRequestNotFoundException extends RuntimeException {

    ActivationRequestNotFoundException(UUID id) {
        super("Solicitação '" + id + "' não encontrada");
    }
}
