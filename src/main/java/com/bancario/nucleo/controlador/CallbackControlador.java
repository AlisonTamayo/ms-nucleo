package com.bancario.nucleo.controlador;

import com.bancario.nucleo.dto.StatusReportDTO;
import com.bancario.nucleo.dto.TransaccionResponseDTO;
import com.bancario.nucleo.servicio.CallbackServicio;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/transacciones")
@RequiredArgsConstructor
@Tag(name = "Callback", description = "Endpoints para recibir resultados de bancos destino")
public class CallbackControlador {

    private final CallbackServicio callbackServicio;

    @PostMapping("/callback")
    @Operation(summary = "Recibir callback de banco destino", description = "Endpoint para que los bancos notifiquen el resultado del procesamiento de una transferencia")
    public ResponseEntity<TransaccionResponseDTO> recibirCallback(
            @Valid @RequestBody StatusReportDTO statusReport) {

        log.info("═══════════════════════════════════════════════════════════════════════════");
        log.info("POST /api/v1/transacciones/callback");
        log.info("  Banco que responde: {}", statusReport.getHeader().getRespondingBankId());
        log.info("  InstructionId: {}", statusReport.getBody().getOriginalInstructionId());
        log.info("  Status: {}", statusReport.getBody().getStatus());
        log.info("═══════════════════════════════════════════════════════════════════════════");

        TransaccionResponseDTO response = callbackServicio.procesarCallback(statusReport);

        if ("COMPLETED".equals(response.getEstado())) {
            return new ResponseEntity<>(response, HttpStatus.OK);
        } else if ("REJECTED".equals(response.getEstado())) {
            return new ResponseEntity<>(response, HttpStatus.OK);
        }

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @GetMapping("/callback/health")
    @Operation(summary = "Health check del endpoint de callback")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Callback endpoint is healthy");
    }
}
