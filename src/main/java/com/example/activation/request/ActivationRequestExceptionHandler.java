package com.example.activation.request;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ActivationRequestExceptionHandler {

    @ExceptionHandler(ActivationRequestConflictException.class)
    ResponseEntity<ProblemDetail> handleConflict(ActivationRequestConflictException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Solicitação conflitante");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
