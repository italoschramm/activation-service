package com.example.activation.request;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Solicitações de ativação")
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Criar uma solicitação de ativação",
            description = "A solicitação é processada em segundo plano. Repetir o mesmo externalId e conteúdo retorna o registro existente.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Solicitação criada"),
            @ApiResponse(responseCode = "200", description = "Solicitação idêntica já existente"),
            @ApiResponse(responseCode = "400", description = "Dados inválidos"),
            @ApiResponse(responseCode = "409", description = "Mesmo externalId com conteúdo diferente")
    })
    public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest input) {
        ActivationRequestService.CreationResult result = service.create(input);
        if (!result.created()) {
            return ResponseEntity.ok(result.response());
        }
        URI location = URI.create("/api/v1/requests/" + result.response().id());
        return ResponseEntity.created(location).body(result.response());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar uma solicitação pelo ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Solicitação encontrada"),
            @ApiResponse(responseCode = "400", description = "ID inválido"),
            @ApiResponse(responseCode = "404", description = "Solicitação não encontrada")
    })
    public ResponseEntity<ActivationRequestResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id)
                .orElseThrow(() -> new ActivationRequestNotFoundException(id)));
    }
}
