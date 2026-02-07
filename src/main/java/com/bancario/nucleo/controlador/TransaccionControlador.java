package com.bancario.nucleo.controlador;

import com.bancario.nucleo.dto.TransaccionResponseDTO;
import com.bancario.nucleo.dto.ReturnRequestDTO;
import com.bancario.nucleo.servicio.TransaccionServicio;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/transacciones")
@RequiredArgsConstructor
@Tag(name = "Orquestador Nucleo", description = "Endpoints para la gestión y orquestación de transacciones bancarias")
public class TransaccionControlador {

    private final TransaccionServicio transaccionServicio;

    @GetMapping
    @Operation(summary = "Listar últimas transacciones", description = "Dashboard endpoint")
    public ResponseEntity<List<TransaccionResponseDTO>> listarTransacciones() {
        return ResponseEntity.ok(transaccionServicio.listarUltimasTransacciones());
    }

    @PostMapping
    @Operation(summary = "Procesar transacción ISO 20022", description = "Endpoint estándar para interoperabilidad")
    public ResponseEntity<TransaccionResponseDTO> crearTransaccion(
            @Valid @RequestBody com.bancario.nucleo.dto.iso.MensajeISO mensajeIso) {
        log.info("Recibido mensaje ISO: {}", mensajeIso.getHeader().getMessageId());
        TransaccionResponseDTO response = transaccionServicio.procesarTransaccionIso(mensajeIso);

        if ("FAILED".equals(response.getEstado())) {
            return new ResponseEntity<>(response, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if ("TIMEOUT".equals(response.getEstado())) {
            return new ResponseEntity<>(response, HttpStatus.GATEWAY_TIMEOUT);
        }

        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar estado de una transacción", description = "Obtiene los detalles y el estado actual de una transacción por su ID de instrucción")
    public ResponseEntity<TransaccionResponseDTO> obtenerTransaccion(@PathVariable @NonNull UUID id) {
        log.info("REST request para obtener transacción: {}", id);
        TransaccionResponseDTO response = transaccionServicio.obtenerTransaccion(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/devoluciones")
    @Operation(summary = "Procesar devolución (pacs.004)", description = "Pass-through para el procesamiento de devoluciones en Contabilidad")
    public ResponseEntity<?> procesarDevolucion(@RequestBody ReturnRequestDTO returnRequest) {
        log.info("Recibida solicitud de devolución: {}",
                (returnRequest.getHeader() != null ? returnRequest.getHeader().getMessageId() : "SIN_HEADER"));
        Object response = transaccionServicio.procesarDevolucion(returnRequest);

        if (response == null) {
            Map<String, String> jsonResponse = new HashMap<>();
            jsonResponse.put("status", "COMPLETED");
            jsonResponse.put("message", "Devolución procesada exitosamente.");
            return ResponseEntity.ok(jsonResponse);
        }

        if (response instanceof String) {
            Map<String, String> jsonResponse = new HashMap<>();
            jsonResponse.put("status", "INFO");
            jsonResponse.put("message", (String) response);
            return ResponseEntity.ok(jsonResponse);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/busqueda")
    @Operation(summary = "Buscador Avanzado", description = "Filtros para Traceability")
    public ResponseEntity<List<TransaccionResponseDTO>> buscar(
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String bic,
            @RequestParam(required = false) String estado) {
        return ResponseEntity.ok(transaccionServicio.buscarTransacciones(id, bic, estado));
    }

    @GetMapping("/stats")
    @Operation(summary = "KPIs Dashboard", description = "Métricas tiempo real para Torre de Control")
    public ResponseEntity<?> obtenerStats() {
        return ResponseEntity.ok(transaccionServicio.obtenerEstadisticas());
    }
}
