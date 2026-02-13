package com.bancario.nucleo.controlador;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controlador para mantener compatibilidad con rutas legacy requeridas por el
 * APIM.
 */
@RestController
@Tag(name = "Compatibilidad Legacy", description = "Rutas de compatibilidad para integraciones antiguas o APIM")
public class LegacyCompatibilityControlador {

    /**
     * GET /api/v2/transfers/health
     * Alias de compatibilidad para APIM que redirige lógicamente al health del
     * nucleo.
     */
    @GetMapping("/api/v2/transfers/health")
    @Operation(summary = "Health Check (Legacy Alias)", description = "Alias para /api/v2/switch/health")
    public ResponseEntity<Map<String, String>> healthAlias() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "switch-ms-nucleo",
                "version", "3.0.0",
                "alias", "/api/v2/transfers/health"));
    }
}
