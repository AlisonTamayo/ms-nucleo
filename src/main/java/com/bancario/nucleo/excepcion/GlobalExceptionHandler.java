package com.bancario.nucleo.excepcion;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorDTO> handleBusinessException(BusinessException ex, HttpServletRequest request) {
        log.warn("Business Exception: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "BUSINESS_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDTO> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled Exception: ", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Error Interno: " + ex.toString(),
                request);
    }

    private ResponseEntity<ErrorDTO> buildResponse(HttpStatus status, String codigo, String mensaje,
            HttpServletRequest request) {
        ErrorDTO error = ErrorDTO.builder()
                .codigo(codigo)
                .mensaje(mensaje)
                .fecha(LocalDateTime.now())
                .path(request.getRequestURI())
                .build();
        return new ResponseEntity<>(error, status);
    }
}
