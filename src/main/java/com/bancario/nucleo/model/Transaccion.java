package com.bancario.nucleo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "Transaccion")
public class Transaccion {

    @Id
    @Column(name = "idInstruccion")
    private UUID idInstruccion;

    @Column(name = "idMensaje", length = 100, nullable = false)
    private String idMensaje;

    @Column(name = "referenciaRed", length = 50, nullable = false, unique = true)
    private String referenciaRed;

    @Column(name = "monto", precision = 18, scale = 2, nullable = false)
    private BigDecimal monto;

    @Column(name = "moneda", length = 3, nullable = false)
    private String moneda;

    @Column(name = "codigoBicOrigen", length = 20, nullable = false)
    private String codigoBicOrigen;

    @Column(name = "codigoBicDestino", length = 20, nullable = false)
    private String codigoBicDestino;

    @Column(name = "estado", length = 20, nullable = false)
    private String estado;

    @Column(name = "fechaCreacion")
    private LocalDateTime fechaCreacion;

    public Transaccion() {
    }

    public Transaccion(UUID idInstruccion) {
        this.idInstruccion = idInstruccion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Transaccion that = (Transaccion) o;
        return Objects.equals(idInstruccion, that.idInstruccion);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(idInstruccion);
    }

    @Override
    public String toString() {
        return "Transaccion{" +
                "idInstruccion=" + idInstruccion +
                ", idMensaje='" + idMensaje + '\'' +
                ", referenciaRed='" + referenciaRed + '\'' +
                ", monto=" + monto +
                ", moneda='" + moneda + '\'' +
                ", codigoBicOrigen='" + codigoBicOrigen + '\'' +
                ", codigoBicDestino='" + codigoBicDestino + '\'' +
                ", estado='" + estado + '\'' +
                ", fechaCreacion=" + fechaCreacion +
                '}';
    }
}
