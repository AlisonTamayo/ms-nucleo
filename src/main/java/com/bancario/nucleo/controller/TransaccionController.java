package com.bancario.nucleo.controller;

import com.bancario.nucleo.dto.TransaccionResponseDTO;
import com.bancario.nucleo.dto.ReturnRequestDTO;
import com.bancario.nucleo.model.Transaccion;
import com.bancario.nucleo.service.TransaccionService;
import java.util.List;
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
public class TransaccionController {

    private final TransaccionService transaccionService;

    @GetMapping
    @Operation(summary = "Listar últimas transacciones", description = "Dashboard endpoint")
    public ResponseEntity<List<Transaccion>> listarTransacciones() {
        return ResponseEntity.ok(transaccionService.listarUltimasTransacciones());
    }

    @PostMapping
    @Operation(summary = "Procesar transacción ISO 20022", description = "Endpoint estándar para interoperabilidad")
    public ResponseEntity<TransaccionResponseDTO> crearTransaccion(
            @Valid @RequestBody com.bancario.nucleo.dto.iso.MensajeISO mensajeIso) {
        log.info("Recibido mensaje ISO: {}", mensajeIso.getHeader().getMessageId());
        TransaccionResponseDTO response = transaccionService.procesarTransaccionIso(mensajeIso);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar estado de una transacción", description = "Obtiene los detalles y el estado actual de una transacción por su ID de instrucción")
    public ResponseEntity<TransaccionResponseDTO> obtenerTransaccion(@PathVariable @NonNull UUID id) {
        log.info("REST request para obtener transacción: {}", id);
        TransaccionResponseDTO response = transaccionService.obtenerTransaccion(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/devoluciones")
    @Operation(summary = "Procesar devolución (pacs.004)", description = "Pass-through para el procesamiento de devoluciones en Contabilidad")
    public ResponseEntity<?> procesarDevolucion(@RequestBody ReturnRequestDTO returnRequest) {
        log.info("Recibida solicitud de devolución: {}", returnRequest.getHeader().getMessageId());
        Object response = transaccionService.procesarDevolucion(returnRequest);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/busqueda")
    @Operation(summary = "Buscador Avanzado", description = "Filtros para Traceability")
    public ResponseEntity<List<Transaccion>> buscar(
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String bic,
            @RequestParam(required = false) String estado) {
        return ResponseEntity.ok(transaccionService.buscarTransacciones(id, bic, estado));
    }

    @GetMapping("/stats")
    @Operation(summary = "KPIs Dashboard", description = "Métricas tiempo real para Torre de Control")
    public ResponseEntity<?> obtenerStats() {
        return ResponseEntity.ok(transaccionService.obtenerEstadisticas());
    }
}
