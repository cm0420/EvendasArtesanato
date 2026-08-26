package com.TadeuArtesanatos.demo.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.jsonwebtoken.ExpiredJwtException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // ---- Hierarquia própria de negócio — um handler cobre todas as subclasses ----

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(buildBody(ex.getStatus(), ex.getMessage()));
    }

    // ---- Autenticação / JWT ----

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(buildBody(HttpStatus.UNAUTHORIZED, "Credenciais inválidas"));
    }

    @ExceptionHandler(ExpiredJwtException.class)
    public ResponseEntity<Map<String, Object>> handleExpiredJwt(ExpiredJwtException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(buildBody(HttpStatus.UNAUTHORIZED, "Token expirado, faça login novamente"));
    }

    // ---- Validação de Bean Validation (@Valid nos DTOs) ----

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Dados inválidos");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(buildBody(HttpStatus.BAD_REQUEST, message));
    }

    // ---- Fallback de compatibilidade — módulos existentes ainda usam essas diretamente ----

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(buildBody(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(buildBody(HttpStatus.CONFLICT, ex.getMessage()));
    }

    // ---- Fallback final — qualquer exceção não tratada não vaza detalhe interno ----

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildBody(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno inesperado"));
    }

    private Map<String, Object> buildBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
