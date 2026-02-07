package com.bancario.nucleo.servicio;

import com.bancario.nucleo.modelo.IsoError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class NormalizadorErroresServicio {

    private final Map<String, IsoError> mapaErroresConocidos;

    public NormalizadorErroresServicio() {
        this.mapaErroresConocidos = new HashMap<>();

        mapaErroresConocidos.put("Error 99", IsoError.AM04);
        mapaErroresConocidos.put("Saldo Insuficiente", IsoError.AM04);
        mapaErroresConocidos.put("INSUFFICIENT_FUNDS", IsoError.AM04);
        mapaErroresConocidos.put("Cuenta Cerrada", IsoError.AC04);
        mapaErroresConocidos.put("Cte Cerrada", IsoError.AC04);
        mapaErroresConocidos.put("Account Not Found", IsoError.AC04);
        mapaErroresConocidos.put("AC01", IsoError.AC01);
        mapaErroresConocidos.put("DUPL", IsoError.DUPL);
        mapaErroresConocidos.put("999", IsoError.MS03);
        mapaErroresConocidos.put("System Error", IsoError.MS03);
        mapaErroresConocidos.put("TIMEOUT", IsoError.MS03);
    }

    public String normalizarError(String errorBanco) {
        if (errorBanco == null || errorBanco.isBlank()) {
            return IsoError.MS03.getCodigo();
        }

        for (Map.Entry<String, IsoError> entry : mapaErroresConocidos.entrySet()) {
            if (errorBanco.contains(entry.getKey())) {
                log.info("Normalización Exitosa: '{}' -> '{}' ({})", errorBanco, entry.getValue().getCodigo(),
                        entry.getValue().getDescripcion());
                return entry.getValue().getCodigo();
            }
        }

        for (IsoError iso : IsoError.values()) {
            if (errorBanco.contains(iso.getCodigo())) {
                return iso.getCodigo();
            }
        }

        log.warn("Error No Clasificado recibido del banco: '{}'. Asignando MS03.", errorBanco);
        return IsoError.MS03.getCodigo();
    }
}
