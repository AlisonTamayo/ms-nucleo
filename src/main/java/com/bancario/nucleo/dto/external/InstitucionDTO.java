package com.bancario.nucleo.dto.external;

import lombok.Data;

@Data
public class InstitucionDTO {
    private String codigoBic;
    private String nombre;
    private String urlDestino;
    private String estadoOperativo;

    public String getCodigoBic() {
        return codigoBic;
    }

    public void setCodigoBic(String codigoBic) {
        this.codigoBic = codigoBic;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getUrlDestino() {
        return urlDestino;
    }

    public void setUrlDestino(String urlDestino) {
        this.urlDestino = urlDestino;
    }

    public String getEstadoOperativo() {
        return estadoOperativo;
    }

    public void setEstadoOperativo(String estadoOperativo) {
        this.estadoOperativo = estadoOperativo;
    }
}