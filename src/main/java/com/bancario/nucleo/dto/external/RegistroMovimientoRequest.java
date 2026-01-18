package com.bancario.nucleo.dto.external;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegistroMovimientoRequest {
    private String codigoBic;
    private UUID idInstruccion;
    private BigDecimal monto;
    private String tipo;
}