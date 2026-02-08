package com.bancario.nucleo.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountLookupResponseDTO {
    private String status;
    private LookupData data;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LookupData {
        private boolean exists;
        private String ownerName;
        private String currency;
        private String status;
        private String mensaje;
        // Fields for alias/error mapping
        private String accountName;
    }
}
