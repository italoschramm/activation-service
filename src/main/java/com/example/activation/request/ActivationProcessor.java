package com.example.activation.request;

import com.example.activation.provider.ActivationCommand;
import com.example.activation.provider.ActivationProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ActivationProcessor {

    private final ActivationRequestRepository repository;
    private final ActivationRequestService service;
    private final ActivationProvider provider;

    public ActivationProcessor(ActivationRequestRepository repository, ActivationRequestService service,
                               ActivationProvider provider) {
        this.repository = repository;
        this.service = service;
        this.provider = provider;
    }

    @Scheduled(cron = "${activation.processing.cron:*/1 * * * * *}")
    public void processPending() {
        Instant now = Instant.now();
        repository.failStaleExhausted(now, now.minusSeconds(30),
                ActivationStatus.PROCESSING, ActivationStatus.FAILED);
        repository.recoverStale(now, now.minusSeconds(30), ActivationStatus.PENDING, ActivationStatus.PROCESSING);

        for (ActivationRequest candidate : repository
                .findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(ActivationStatus.PENDING, now)) {
            if (repository.claim(candidate.getId(), Instant.now(),
                    ActivationStatus.PENDING, ActivationStatus.PROCESSING) == 0) {
                continue;
            }

            ActivationProvider.Outcome outcome = provider.activate(new ActivationCommand(
                    candidate.getExternalId(), candidate.getCustomerId(), candidate.getProductCode()));
            switch (outcome) {
                case COMPLETED -> service.complete(candidate.getId());
                case REJECTED -> service.fail(candidate.getId());
                case RETRYABLE_FAILURE -> service.retryOrFail(candidate.getId());
            }
        }
    }
}
