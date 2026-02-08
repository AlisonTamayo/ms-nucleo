package com.bancario.nucleo.mapper;

import com.bancario.nucleo.dto.TransaccionResponseDTO;
import com.bancario.nucleo.modelo.Transaccion;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class TransaccionMapper {

    public TransaccionResponseDTO toDTO(Transaccion entidad) {
        if (entidad == null)
            return null;

        return TransaccionResponseDTO.builder()
                .idInstruccion(entidad.getIdInstruccion())
                .idMensaje(entidad.getIdMensaje())
                .referenciaRed(entidad.getReferenciaRed())
                .monto(entidad.getMonto())
                .moneda(entidad.getMoneda())
                .codigoBicOrigen(entidad.getCodigoBicOrigen())
                .codigoBicDestino(entidad.getCodigoBicDestino())
                .estado(entidad.getEstado())
                .fechaCreacion(entidad.getFechaCreacion())
                .codigoReferencia(entidad.getCodigoReferencia())
                .build();
    }

    public List<TransaccionResponseDTO> toDTOList(List<Transaccion> entidades) {
        if (entidades == null)
            return List.of();
        return entidades.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
}
