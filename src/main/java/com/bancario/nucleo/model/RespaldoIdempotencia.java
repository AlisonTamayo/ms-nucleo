package com.bancario.nucleo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "RespaldoIdempotencia")
public class RespaldoIdempotencia {

    @Id
    @Column(name = "idInstruccion")
    private UUID idInstruccion;

    @Column(name = "hashContenido", nullable = false)
    private String hashContenido;

    @Column(name = "cuerpoRespuesta", columnDefinition = "text")
    private String cuerpoRespuesta;

    @Column(name = "fechaExpiracion", nullable = false)
    private LocalDateTime fechaExpiracion;

    @OneToOne
    @MapsId
    @JoinColumn(name = "idInstruccion")
    private Transaccion transaccion;

    public RespaldoIdempotencia() {
    }

    public RespaldoIdempotencia(UUID idInstruccion) {
        this.idInstruccion = idInstruccion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        RespaldoIdempotencia that = (RespaldoIdempotencia) o;
        return Objects.equals(idInstruccion, that.idInstruccion);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(idInstruccion);
    }

    @Override
    public String toString() {
        return "RespaldoIdempotencia{" +
                "idInstruccion=" + idInstruccion +
                ", hashContenido='" + hashContenido + '\'' +
                ", cuerpoRespuesta='" + cuerpoRespuesta + '\'' +
                ", fechaExpiracion=" + fechaExpiracion +
                '}';
    }
}
