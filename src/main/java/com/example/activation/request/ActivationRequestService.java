package com.example.activation.request;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class ActivationRequestService {

    private final ActivationRequestRepository repository;

    public ActivationRequestService(ActivationRequestRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ActivationRequestResponse create(CreateActivationRequest input) {
        ActivationRequest request = new ActivationRequest(
                input.externalId(), input.customerId(), input.productCode());
        return ActivationRequestResponse.from(repository.save(request));
    }

    @Transactional(readOnly = true)
    public Optional<ActivationRequestResponse> findById(UUID id) {
        return repository.findById(id).map(ActivationRequestResponse::from);
    }
}
