package com.bancario.nucleo.repositorio;

import com.bancario.nucleo.modelo.RespaldoIdempotencia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RespaldoIdempotenciaRepositorio extends JpaRepository<RespaldoIdempotencia, UUID> {
    java.util.Optional<RespaldoIdempotencia> findByHashContenido(String hashContenido);
}
