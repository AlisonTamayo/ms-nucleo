package com.bancario.nucleo.controlador;

import com.bancario.nucleo.dto.AccountLookupRequestDTO;
import com.bancario.nucleo.dto.AccountLookupResponseDTO;
import com.bancario.nucleo.servicio.TransaccionServicio;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/cuentas")
@RequiredArgsConstructor
@Tag(name = "Servicio de Cuentas", description = "Validación y consulta de cuentas interbancarias")
public class CuentaControlador {

    private final TransaccionServicio transaccionServicio;

    @PostMapping("/lookup")
    @Operation(summary = "Validar Cuenta Destino", description = "Account Lookup (acmt.023) - Proxy hacia el banco destino")
    public ResponseEntity<AccountLookupResponseDTO> validarCuenta(@Valid @RequestBody AccountLookupRequestDTO request) {
        log.info("Recibida solicitud de Account Lookup para banco: {}",
                (request.getBody() != null ? request.getBody().getTargetBankId() : "UNKNOWN"));
        return ResponseEntity.ok(transaccionServicio.validarCuentaDestino(request));
    }
}
