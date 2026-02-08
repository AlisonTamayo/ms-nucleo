package com.bancario.nucleo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusReportDTO {

    @NotNull
    private Header header;

    @NotNull
    private Body body;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Header {
        private String messageId;
        private String creationDateTime;
        @NotBlank
        private String respondingBankId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Body {
        @NotNull
        private UUID originalInstructionId;
        private String originalMessageId;

        @NotBlank
        private String status;

        private String reasonCode;

        private String reasonDescription;
        private String processedDateTime;
    }
}
