package com.example.activation.request;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
class ActivationRequestExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ActivationRequestExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception,
                                                   HttpServletRequest request) {
        ProblemDetail problem = newProblem(HttpStatus.BAD_REQUEST, "Dados inválidos",
                "Um ou mais campos não passaram na validação.", request);
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "valor inválido" : error.getDefaultMessage()))
                .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException exception,
                                                       HttpServletRequest request) {
        return ResponseEntity.badRequest().body(newProblem(HttpStatus.BAD_REQUEST, "Dados inválidos",
                "O corpo da requisição deve ser um JSON válido.", request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleInvalidPath(MethodArgumentTypeMismatchException exception,
                                                     HttpServletRequest request) {
        return ResponseEntity.badRequest().body(newProblem(HttpStatus.BAD_REQUEST, "Parâmetro inválido",
                "O parâmetro '" + exception.getName() + "' tem formato inválido.", request));
    }

    @ExceptionHandler(ActivationRequestNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ActivationRequestNotFoundException exception,
                                                 HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(newProblem(
                HttpStatus.NOT_FOUND, "Solicitação não encontrada", exception.getMessage(), request));
    }

    @ExceptionHandler(ActivationRequestConflictException.class)
    ResponseEntity<ProblemDetail> handleConflict(ActivationRequestConflictException exception,
                                                 HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(newProblem(
                HttpStatus.CONFLICT, "Solicitação conflitante", exception.getMessage(), request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = error.getStatusCode();
            return ResponseEntity.status(status).body(newProblem(
                    status, "Requisição inválida", "A requisição não pôde ser atendida.", request));
        }
        String traceId = UUID.randomUUID().toString();
        logger.error("Erro inesperado traceId={} method={} path={}",
                traceId, request.getMethod(), request.getRequestURI(), exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocorreu um erro interno. Informe o traceId ao suporte.");
        problem.setTitle("Erro interno");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("traceId", traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private ProblemDetail newProblem(HttpStatusCode status, String title, String detail,
                                     HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        logger.warn("Erro de API traceId={} status={} method={} path={}",
                traceId, status.value(), request.getMethod(), request.getRequestURI());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("traceId", traceId);
        return problem;
    }
}
