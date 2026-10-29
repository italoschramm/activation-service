package com.example.activation.request;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID> {
    Optional<ActivationRequest> findByExternalId(String externalId);

    List<ActivationRequest> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            ActivationStatus status, Instant now);

    @Modifying
    @Transactional
    @Query("""
            update ActivationRequest request
            set request.status = :processing,
                request.attemptCount = request.attemptCount + 1,
                request.updatedAt = :now
            where request.id = :id
              and request.status = :pending
              and request.nextAttemptAt <= :now
              and request.attemptCount < 3
            """)
    int claim(@Param("id") UUID id, @Param("now") Instant now,
              @Param("pending") ActivationStatus pending, @Param("processing") ActivationStatus processing);

    @Modifying
    @Transactional
    @Query("""
            update ActivationRequest request
            set request.status = :pending,
                request.nextAttemptAt = :now,
                request.updatedAt = :now
            where request.status = :processing
              and request.updatedAt < :cutoff
              and request.attemptCount < 3
            """)
    int recoverStale(@Param("now") Instant now, @Param("cutoff") Instant cutoff,
                     @Param("pending") ActivationStatus pending,
                     @Param("processing") ActivationStatus processing);

    @Modifying
    @Transactional
    @Query("""
            update ActivationRequest request
            set request.status = :failed,
                request.updatedAt = :now
            where request.status = :processing
              and request.updatedAt < :cutoff
              and request.attemptCount >= 3
            """)
    int failStaleExhausted(@Param("now") Instant now, @Param("cutoff") Instant cutoff,
                           @Param("processing") ActivationStatus processing,
                           @Param("failed") ActivationStatus failed);
}
