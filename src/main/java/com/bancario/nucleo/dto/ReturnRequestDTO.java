package com.bancario.nucleo.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReturnRequestDTO {
    private Header header;
    private Body body;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Header {
        private String messageId;
        private String creationDateTime;
        private String originatingBankId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Body {
        private String returnInstructionId;
        private String originalInstructionId;
        private String returnReason;
        private Amount returnAmount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Amount {
        private String currency;
        private BigDecimal value;
    }
}
