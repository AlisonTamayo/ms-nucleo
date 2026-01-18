package com.bancario.nucleo.dto.iso;

import lombok.Data;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;

@Data
public class BodyISO {
    @NotBlank
    private String instructionId; 
    
    private String endToEndId;    
    
    @Valid
    @NotNull
    private AmountISO amount;
    
    @Valid
    @NotNull
    private ActorISO debtor;   
    
    @Valid
    @NotNull
    private ActorISO creditor;
    
    private String remittanceInformation; 
}