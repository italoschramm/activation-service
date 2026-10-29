package com.example.activation.request;

import org.springframework.dao.DataIntegrityViolationException;
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

    public CreationResult create(CreateActivationRequest input) {
        try {
            ActivationRequest request = new ActivationRequest(
                    input.externalId(), input.customerId(), input.productCode());
            return new CreationResult(ActivationRequestResponse.from(repository.saveAndFlush(request)), true);
        } catch (DataIntegrityViolationException exception) {
            // The repository transaction has rolled back before this lookup runs.
            ActivationRequest existing = repository.findByExternalId(input.externalId())
                    .orElseThrow(() -> exception);
            if (existing.getCustomerId().equals(input.customerId())
                    && existing.getProductCode().equals(input.productCode())) {
                return new CreationResult(ActivationRequestResponse.from(existing), false);
            }
            throw new ActivationRequestConflictException(input.externalId());
        }
    }

    @Transactional(readOnly = true)
    public Optional<ActivationRequestResponse> findById(UUID id) {
        return repository.findById(id).map(ActivationRequestResponse::from);
    }

    public record CreationResult(ActivationRequestResponse response, boolean created) {
    }
}
