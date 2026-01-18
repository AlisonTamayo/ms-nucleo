package com.bancario.nucleo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.lang.NonNull;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransaccionRequestDTO {

    @NonNull
    @NotNull(message = "El ID de instrucción es obligatorio")
    private UUID idInstruccion;

    @NotBlank(message = "El ID de mensaje es obligatorio")
    @Size(max = 100)
    private String idMensaje;

    @NotBlank(message = "La referencia de red es obligatoria")
    @Size(max = 50)
    private String referenciaRed;

    @NotNull(message = "El monto es obligatorio")
    private BigDecimal monto;

    @NotBlank(message = "La moneda es obligatoria")
    @Pattern(regexp = "^[A-Z]{3}$", message = "La moneda debe ser un código ISO de 3 caracteres")
    private String moneda;

    @NotBlank(message = "El BIC de origen es obligatorio")
    @Size(max = 20)
    private String codigoBicOrigen;

    @NotBlank(message = "El BIC de destino es obligatorio")
    @Size(max = 20)
    private String codigoBicDestino;
}
