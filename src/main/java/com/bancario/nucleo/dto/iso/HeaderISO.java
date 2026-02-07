package com.bancario.nucleo.dto.iso;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class HeaderISO {
    @NotBlank
    private String messageId;        
    private String creationDateTime; 
    @NotBlank
    private String originatingBankId; 
}