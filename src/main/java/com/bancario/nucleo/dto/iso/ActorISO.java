package com.bancario.nucleo.dto.iso;

import lombok.Data;

@Data
public class ActorISO {
    private String name;
    private String accountId;
    private String accountType;
    private String targetBankId;
}