package com.bancario.nucleo.controlador;

import com.bancario.nucleo.dto.TransaccionResponseDTO;
import com.bancario.nucleo.servicio.TransaccionServicio;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador compatible con AWS APIM (API Gateway).
 * Expone endpoints en /api/v2/switch/* que coinciden con las rutas configuradas
 * en el APIM.
 * 
 * El APIM ya validó autenticación (JWT Cognito) y rate limiting.
 * Este controlador solo procesa la lógica de negocio.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/switch")
@RequiredArgsConstructor
@Tag(name = "APIM Switch", description = "Endpoints expuestos via AWS API Gateway para interoperabilidad bancaria")
public class ApimSwitchControlador {

    private final TransaccionServicio transaccionServicio;

    /**
     * POST /api/v2/switch/transfers
     * Ruta configurada en APIM para crear transferencias interbancarias.
     * Scope requerido (validado por APIM): https://switch-api.com/transfers.write
     */
    @PostMapping("/transfers")
    @Operation(summary = "Crear Transferencia Interbancaria", description = "Endpoint ISO 20022 para transferencias. Autenticado via Cognito JWT en APIM.")
    public ResponseEntity<TransaccionResponseDTO> crearTransferencia(
            @Valid @RequestBody com.bancario.nucleo.dto.iso.MensajeISO mensajeIso) {

        log.info("[APIM] Recibida transferencia - MessageId: {}, TraceId: {}",
                mensajeIso.getHeader().getMessageId(),
                mensajeIso.getHeader() != null ? mensajeIso.getHeader().getMessageId() : "N/A");

        TransaccionResponseDTO response = transaccionServicio.procesarTransaccionIso(mensajeIso);

        // Mapear estados a códigos HTTP apropiados
        return switch (response.getEstado()) {
            case "FAILED" -> new ResponseEntity<>(response, HttpStatus.UNPROCESSABLE_ENTITY);
            case "TIMEOUT" -> new ResponseEntity<>(response, HttpStatus.GATEWAY_TIMEOUT);
            case "QUEUED" -> {
                log.info("[APIM] Transacción {} encolada en RabbitMQ. HTTP 202 Accepted", response.getIdInstruccion());
                yield new ResponseEntity<>(response, HttpStatus.ACCEPTED);
            }
            default -> new ResponseEntity<>(response, HttpStatus.CREATED);
        };
    }

    /**
     * GET /api/v2/switch/transfers/{instructionId}
     * Consulta el estado de una transferencia por su ID de instrucción.
     */
    @GetMapping("/transfers/{instructionId}")
    @Operation(summary = "Consultar Estado de Transferencia", description = "Obtiene el estado actual de una transferencia ISO 20022")
    public ResponseEntity<TransaccionResponseDTO> consultarEstado(
            @PathVariable("instructionId") java.util.UUID instructionId) {

        log.info("[APIM] Consulta de estado - InstructionId: {}", instructionId);
        TransaccionResponseDTO response = transaccionServicio.obtenerTransaccion(instructionId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /health
     * Health check para el ALB Target Group.
     * El ALB usa este endpoint para validar que el pod está listo para recibir
     * tráfico.
     */
    @GetMapping("/health")
    public ResponseEntity<java.util.Map<String, String>> health() {
        return ResponseEntity.ok(java.util.Map.of(
                "status", "UP",
                "service", "switch-ms-nucleo",
                "version", "3.0.0"));
    }

    /**
     * POST /api/v2/switch/account-lookup
     * Validación de cuenta destino (Account Lookup - acmt.023).
     * Ruta compatible con APIM para consultar si una cuenta existe en un banco.
     * 
     * Este endpoint hace proxy al banco destino para validar la cuenta.
     * Scope requerido (validado por APIM): https://switch-api.com/transfers.write
     */
    @PostMapping("/account-lookup")
    @Operation(summary = "Validar Cuenta Destino (Account Lookup)", description = "ISO 20022 acmt.023 - Consulta si una cuenta existe en el banco destino y obtiene datos del titular")
    public ResponseEntity<com.bancario.nucleo.dto.AccountLookupResponseDTO> validarCuenta(
            @Valid @RequestBody com.bancario.nucleo.dto.AccountLookupRequestDTO request) {

        log.info("[APIM] Account Lookup - Banco: {}, Cuenta: {}",
                request.getBody() != null ? request.getBody().getTargetBankId() : "UNKNOWN",
                request.getBody() != null ? request.getBody().getTargetAccountNumber() : "UNKNOWN");

        com.bancario.nucleo.dto.AccountLookupResponseDTO response = transaccionServicio.validarCuentaDestino(request);

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v2/switch/returns
     * Procesamiento de devoluciones/reversos (ISO 20022 pacs.004).
     * Ruta compatible con APIM para procesar devoluciones de transferencias.
     * 
     * Este endpoint delega al microservicio de Devolución o Contabilidad según el
     * caso.
     * Scope requerido (validado por APIM): https://switch-api.com/transfers.write
     */
    @PostMapping("/returns")
    @Operation(summary = "Procesar Devolución/Reverso", description = "ISO 20022 pacs.004 - Procesa devoluciones de transferencias interbancarias")
    public ResponseEntity<?> procesarDevolucion(
            @Valid @RequestBody com.bancario.nucleo.dto.ReturnRequestDTO returnRequest) {

        log.info("[APIM] Devolución recibida - MessageId: {}, OriginalTxId: {}",
                returnRequest.getHeader() != null ? returnRequest.getHeader().getMessageId() : "UNKNOWN",
                returnRequest.getBody() != null ? returnRequest.getBody().getOriginalInstructionId() : "UNKNOWN");

        Object response = transaccionServicio.procesarDevolucion(returnRequest);

        // Si la respuesta es null, retornar mensaje genérico de éxito
        if (response == null) {
            return ResponseEntity.ok(java.util.Map.of(
                    "status", "COMPLETED",
                    "message", "Devolución procesada exitosamente"));
        }

        // Si es String, convertir a JSON
        if (response instanceof String) {
            return ResponseEntity.ok(java.util.Map.of(
                    "status", "INFO",
                    "message", (String) response));
        }

        return ResponseEntity.ok(response);
    }
}
