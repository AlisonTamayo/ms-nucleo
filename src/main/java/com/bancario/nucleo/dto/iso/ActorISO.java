package com.bancario.nucleo.dto.iso;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonAlias;

@Data
public class ActorISO {
    private String name;
    private String accountId;
    private String accountType;

    @JsonAlias("bankId")
    private String targetBankId;
}