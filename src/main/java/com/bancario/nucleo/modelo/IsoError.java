package com.bancario.nucleo.modelo;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum IsoError {
    AM04("AM04", "Fondos Insuficientes"),
    AC04("AC04", "Cuenta Cerrada / No Existe"),
    MS03("MS03", "Fallo Técnico / No Especificado"),
    AG01("AG01", "Transacción Prohibida"),
    BE01("BE01", "Inconsistencia Información de Cliente"),
    RC01("RC01", "Error de Sintaxis / Formato"),
    RR04("RR04", "Falta firma o firma inválida");

    private final String codigo;
    private final String descripcion;

    public static IsoError fromString(String text) {
        for (IsoError b : IsoError.values()) {
            if (b.codigo.equalsIgnoreCase(text)) {
                return b;
            }
        }
        return MS03;
    }
}
