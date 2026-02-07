package com.bancario.nucleo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransaccionResponseDTO {
    private UUID idInstruccion;
    private String idMensaje;
    private String referenciaRed;
    private BigDecimal monto;
    private String moneda;
    private String codigoBicOrigen;
    private String codigoBicDestino;
    private String estado;
    private LocalDateTime fechaCreacion;
}
