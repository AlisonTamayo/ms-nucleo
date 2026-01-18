package com.bancario.nucleo.repository;

import com.bancario.nucleo.model.RespaldoIdempotencia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RespaldoIdempotenciaRepository extends JpaRepository<RespaldoIdempotencia, UUID> {
    java.util.Optional<RespaldoIdempotencia> findByHashContenido(String hashContenido);
}
