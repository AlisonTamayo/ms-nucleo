package com.bancario.nucleo.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountLookupRequestDTO {
    private Header header;
    private Body body;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Header {
        private String originatingBankId;
        private String messageId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Body {
        private String targetBankId;
        private String targetAccountNumber;
    }
}
