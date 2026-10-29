package com.example.activation.request;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID> {
}
