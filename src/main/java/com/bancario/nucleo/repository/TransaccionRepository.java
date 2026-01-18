package com.bancario.nucleo.repository;

import com.bancario.nucleo.model.Transaccion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransaccionRepository extends JpaRepository<Transaccion, UUID> {
        Optional<Transaccion> findByReferenciaRed(String referenciaRed);

        @org.springframework.data.jpa.repository.Query("SELECT COUNT(t) FROM Transaccion t WHERE t.fechaCreacion >= :start")
        long countTransaccionesDesde(
                        @org.springframework.data.repository.query.Param("start") java.time.LocalDateTime start);

        @org.springframework.data.jpa.repository.Query("SELECT SUM(t.monto) FROM Transaccion t WHERE t.fechaCreacion >= :start AND t.estado = 'COMPLETED'")
        java.math.BigDecimal sumMontoExitosoDesde(
                        @org.springframework.data.repository.query.Param("start") java.time.LocalDateTime start);

        @org.springframework.data.jpa.repository.Query("SELECT t.estado, COUNT(t) FROM Transaccion t WHERE t.fechaCreacion >= :start GROUP BY t.estado")
        java.util.List<Object[]> countPorEstadoDesde(
                        @org.springframework.data.repository.query.Param("start") java.time.LocalDateTime start);

        @org.springframework.data.jpa.repository.Query("SELECT t FROM Transaccion t WHERE " +
                        "(:id IS NULL OR CAST(t.idInstruccion AS string) LIKE %:id%) AND " +
                        "(:bic IS NULL OR t.codigoBicOrigen LIKE %:bic% OR t.codigoBicDestino LIKE %:bic%) AND " +
                        "(:estado IS NULL OR t.estado = :estado)")
        java.util.List<Transaccion> buscarTransacciones(
                        @org.springframework.data.repository.query.Param("id") String id,
                        @org.springframework.data.repository.query.Param("bic") String bic,
                        @org.springframework.data.repository.query.Param("estado") String estado);
}
