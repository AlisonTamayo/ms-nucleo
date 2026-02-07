package com.bancario.nucleo.dto.external;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class RegistroOperacionDTO {
    private UUID idInstruccion;
    private UUID idInstruccionOriginal; // Opcional, para reversos
    private String bicEmisor;
    private String bicReceptor;
    private BigDecimal monto;
    private String tipoOperacion; // PAGO, REVERSO
}
