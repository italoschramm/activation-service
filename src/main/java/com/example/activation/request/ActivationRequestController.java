package com.example.activation.request;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest input) {
        ActivationRequestService.CreationResult result = service.create(input);
        if (!result.created()) {
            return ResponseEntity.ok(result.response());
        }
        URI location = URI.create("/api/v1/requests/" + result.response().id());
        return ResponseEntity.created(location).body(result.response());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActivationRequestResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.of(service.findById(id));
    }
}
