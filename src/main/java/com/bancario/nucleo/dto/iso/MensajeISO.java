package com.bancario.nucleo.dto.iso;

import lombok.Data;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@Data
public class MensajeISO {
    @Valid
    @NotNull
    private HeaderISO header;
    
    @Valid
    @NotNull
    private BodyISO body;
}