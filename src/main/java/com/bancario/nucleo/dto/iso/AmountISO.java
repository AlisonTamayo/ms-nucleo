package com.bancario.nucleo.dto.iso;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class AmountISO {
    private String currency;
    private BigDecimal value;
}