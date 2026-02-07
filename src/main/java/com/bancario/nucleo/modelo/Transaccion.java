package com.bancario.nucleo.modelo;

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

    // Campos agregados para cumplir con especificación DNS
    @Column(name = "cuentaOrigen", length = 34)
    private String cuentaOrigen;

    @Column(name = "cuentaDestino", length = 34)
    private String cuentaDestino;

    @Column(name = "idBeneficiario", length = 20)
    private String idBeneficiario;

    @Column(name = "reintentos")
    private Integer reintentos = 0;

    @Column(name = "codigoError", length = 10)
    private String codigoError;

    @Column(name = "idCicloCompensacion")
    private Integer idCicloCompensacion;

    @Column(name = "fechaEncolado")
    private LocalDateTime fechaEncolado;

    @Column(name = "fechaCompletado")
    private LocalDateTime fechaCompletado;

    // Código de referencia bancario de 6 dígitos para devoluciones
    @Column(name = "codigoReferencia", length = 6, unique = true)
    private String codigoReferencia;

    public Transaccion() {
    }

    public Transaccion(UUID idInstruccion) {
        this.idInstruccion = idInstruccion;
    }

    /**
     * Genera un código de referencia bancario de 6 dígitos numéricos.
     * Ejemplo: "847293"
     */
    public static String generarCodigoReferencia() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        int numero = 100000 + random.nextInt(900000); // Rango: 100000-999999
        return String.valueOf(numero);
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
