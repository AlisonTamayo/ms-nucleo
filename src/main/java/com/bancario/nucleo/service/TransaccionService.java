package com.bancario.nucleo.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.bancario.nucleo.dto.TransaccionResponseDTO;
import com.bancario.nucleo.dto.ReturnRequestDTO;
import com.bancario.nucleo.dto.external.InstitucionDTO;
import com.bancario.nucleo.dto.external.RegistroMovimientoRequest;
import com.bancario.nucleo.dto.iso.MensajeISO;
import com.bancario.nucleo.exception.BusinessException;
import com.bancario.nucleo.model.RespaldoIdempotencia;
import com.bancario.nucleo.model.Transaccion;
import com.bancario.nucleo.repository.RespaldoIdempotenciaRepository;
import com.bancario.nucleo.repository.TransaccionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransaccionService {

    @Autowired
    private StringRedisTemplate redisTemplate;
    private final TransaccionRepository transaccionRepository;
    private final RespaldoIdempotenciaRepository idempotenciaRepository;
    private final RestTemplate restTemplate;
    private final io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry;

    @Value("${service.directorio.url:http://ms-directorio:8081}")
    private String directorioUrl;

    @Value("${service.contabilidad.url:http://ms-contabilidad:8083}")
    private String contabilidadUrl;

    @Value("${service.compensacion.url:http://ms-compensacion:8084}")
    private String compensacionUrl;

    @Value("${service.devolucion.url:http://ms-devolucion:8085}")
    private String devolucionUrl;

    @Transactional
    public TransaccionResponseDTO procesarTransaccionIso(MensajeISO iso) {
        UUID idInstruccion = UUID.fromString(iso.getBody().getInstructionId());
        String bicOrigen = iso.getHeader().getOriginatingBankId();
        String bicDestino = iso.getBody().getCreditor().getTargetBankId();
        BigDecimal monto = iso.getBody().getAmount().getValue();
        String moneda = iso.getBody().getAmount().getCurrency();
        String messageId = iso.getHeader().getMessageId();
        String creationDateTime = iso.getHeader().getCreationDateTime();
        String cuentaOrigen = iso.getBody().getDebtor().getAccountId();
        String cuentaDestino = iso.getBody().getCreditor().getAccountId();

        String fingerprint = idInstruccion.toString() + monto.toString() + moneda + bicOrigen + bicDestino
                + creationDateTime + cuentaOrigen + cuentaDestino;
        String fingerprintMd5 = generarMD5(fingerprint);

        log.info(">>> Iniciando Tx ISO: InstID={} MsgID={} Monto={}", idInstruccion, messageId, monto);

        String redisKey = "idem:" + idInstruccion;
        String redisValue = fingerprintMd5 + "|PROCESSING|-";

        Boolean claimed;
        try {
            claimed = redisTemplate.opsForValue().setIfAbsent(redisKey, redisValue, java.time.Duration.ofHours(24));
        } catch (Exception e) {
            log.error("Fallo Redis SET: {}. Pasando a Fallback DB.", e.getMessage());
            boolean existeEnDb = idempotenciaRepository.findByHashContenido("HASH_" + idInstruccion).isPresent();
            claimed = !existeEnDb;
        }

        if (Boolean.TRUE.equals(claimed)) {
            log.info("Redis CLAIM OK (o Fallback DB) — Nueva transacción {}", idInstruccion);
        } else {
            String existingVal = null;
            try {
                existingVal = redisTemplate.opsForValue().get(redisKey);
            } catch (Exception e) {
                log.warn("Redis GET falló tras saber que es duplicado. Intentando recuperar detalles de DB...");
            }

            if (existingVal == null) {
                return idempotenciaRepository.findByHashContenido("HASH_" + idInstruccion)
                        .map(respaldo -> {
                            log.warn(
                                    "Duplicado detectado via DB (Redis Offline). Retornando original ISO 20022 para {}",
                                    idInstruccion);
                            return mapToDTO(respaldo.getTransaccion());
                        })
                        .orElseThrow(() -> new IllegalStateException(
                                "Inconsistencia: Detectado como duplicado pero no encontrado en DB ni Redis."));
            } else {
                String[] parts = existingVal.split("\\|");
                String storedMd5 = parts[0];

                if (!storedMd5.equals(fingerprintMd5)) {
                    log.error("VIOLACIÓN DE INTEGRIDAD ISO 20022 — InstructionId={} alterado", idInstruccion);
                    throw new SecurityException("Same InstructionId, different content fingerprint");
                }

                log.warn("Duplicado legítimo ISO 20022 (Redis Hit) — Replay {}", idInstruccion);
                return obtenerTransaccion(idInstruccion);
            }
        }

        Transaccion tx = new Transaccion();
        tx.setIdInstruccion(idInstruccion);
        tx.setIdMensaje(messageId);
        String rawRef = monto.toString() + bicOrigen + bicDestino + creationDateTime + cuentaOrigen + cuentaDestino;
        tx.setReferenciaRed(generarMD5(rawRef).toUpperCase());
        tx.setMonto(monto);
        tx.setMoneda(moneda);
        tx.setCodigoBicOrigen(bicOrigen);
        tx.setCodigoBicDestino(bicDestino);
        tx.setEstado("RECEIVED");
        tx.setFechaCreacion(LocalDateTime.now(java.time.ZoneOffset.UTC));

        tx = transaccionRepository.save(tx);

        try {
            String bin = (cuentaDestino != null && cuentaDestino.length() >= 6) ? cuentaDestino.substring(0, 6)
                    : "000000";
            validarEnrutamientoBin(bin, bicDestino);

            validarBanco(bicOrigen, false);
            InstitucionDTO bancoDestinoInfo = validarBanco(bicDestino, true);

            log.info("Ledger: Debitando {} a {}", monto, bicOrigen);
            registrarMovimientoContable(bicOrigen, idInstruccion, monto, "DEBIT");

            log.info("Ledger: Acreditando {} a {}", monto, bicDestino);
            registrarMovimientoContable(bicDestino, idInstruccion, monto, "CREDIT");

            log.info("Clearing: Registrando posiciones en Ciclo ABIERTO (Auto)");
            notificarCompensacion(bicOrigen, monto, true);
            notificarCompensacion(bicDestino, monto, false);

            int[] tiemposEspera = { 0, 800, 2000, 4000 };
            boolean entregado = false;
            String ultimoError = "";
            int fallosConsecutivos = 0;

            for (int intento = 0; intento < tiemposEspera.length; intento++) {
                if (tiemposEspera[intento] > 0) {
                    try {
                        Thread.sleep(tiemposEspera[intento]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }

                try {
                    String urlWebhook = bancoDestinoInfo.getUrlDestino();
                    log.info("Intento #{}: Enviando a {}", intento + 1, urlWebhook);

                    io.github.resilience4j.circuitbreaker.CircuitBreaker cb = circuitBreakerRegistry
                            .circuitBreaker(bicDestino);

                    try {
                        cb.executeRunnable(() -> {
                            restTemplate.postForEntity(urlWebhook, iso, String.class);
                        });

                        log.info("Webhook: Entregado exitosamente.");
                        entregado = true;
                        break;

                    } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
                        log.error("CIRCUIT BREAKER ABIERTO para {}. Transaccion rechazada inmediatamente.", bicDestino);
                        throw new BusinessException("MS03 - El Banco Destino " + bicDestino
                                + " está NO DISPONIBLE (Circuit Breaker Activo).");
                    }
                } catch (BusinessException e) {
                    throw e;
                } catch (HttpClientErrorException e) {
                    log.error("Rechazo 4xx del Banco Destino: {}", e.getStatusCode());
                    throw new BusinessException("Banco Destino rechazó la transacción: " + e.getStatusCode());

                } catch (org.springframework.web.client.HttpServerErrorException e) {
                    log.error("Error 5xx del Banco Destino: {}", e.getStatusCode());
                    reportarFalloAlDirectorio(bicDestino, "HTTP_5XX");
                    throw new BusinessException("Banco Destino falló internamente (5xx): " + e.getStatusCode());

                } catch (org.springframework.web.client.ResourceAccessException e) {
                    log.error("Timeout/Conexión fallida con Banco Destino: {}", e.getMessage());
                    reportarFalloAlDirectorio(bicDestino, "TIMEOUT_CONEXION");
                    ultimoError = "Timeout/Conexión: " + e.getMessage();

                } catch (Exception e) {
                    log.warn("Fallo intento #{}: {}", intento + 1, e.getMessage());
                    circuitBreakerRegistry.circuitBreaker(bicDestino).onError(0, java.util.concurrent.TimeUnit.SECONDS,
                            e);
                    ultimoError = e.getMessage();
                }
            }

            if (entregado) {
                tx.setEstado("COMPLETED");
                guardarRespaldoIdempotencia(tx, "EXITO");
            } else {
                log.error("TIMEOUT: Se agotaron los reintentos. Último error: {}", ultimoError);
                log.error("TIMEOUT: Se agotaron los reintentos. Último error: {}", ultimoError);
                tx.setEstado("TIMEOUT");
                transaccionRepository.save(tx);

                throw new java.util.concurrent.TimeoutException("No se obtuvo respuesta del Banco Destino");
            }

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("Transacción en estado PENDING por Timeout: {}", e.getMessage());
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "Tiempo de espera agotado con Banco Destino");

        } catch (BusinessException e) {
            log.error("Error de Negocio: {}", e.getMessage());
            ejecutarReversoSaga(tx);
            tx.setEstado("FAILED");
        } catch (Exception e) {
            log.error("Error crítico en Tx: {}", e.getMessage());
            ejecutarReversoSaga(tx);
            tx.setEstado("FAILED");
        }

        Transaccion saved = transaccionRepository.save(tx);
        return mapToDTO(saved);
    }

    public TransaccionResponseDTO obtenerTransaccion(UUID id) {
        Transaccion tx = transaccionRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Transacción no encontrada"));

        if ("PENDING".equals(tx.getEstado()) || "RECEIVED".equals(tx.getEstado()) || "TIMEOUT".equals(tx.getEstado())) {

            if (tx.getFechaCreacion() != null &&
                    tx.getFechaCreacion().plusSeconds(5).isBefore(LocalDateTime.now(java.time.ZoneOffset.UTC))) {

                log.info("RF-04: Transacción {} en estado incierto. Iniciando SONDING al Banco Destino...", id);

                try {
                    InstitucionDTO bancoDestino = validarBanco(tx.getCodigoBicDestino(), true);
                    String urlConsulta = bancoDestino.getUrlDestino() + "/status/" + id;

                    try {
                        TransaccionResponseDTO respuestaBanco = restTemplate.getForObject(urlConsulta,
                                TransaccionResponseDTO.class);

                        if (respuestaBanco != null && respuestaBanco.getEstado() != null) {
                            String nuevoEstado = respuestaBanco.getEstado();
                            if ("COMPLETED".equals(nuevoEstado) || "FAILED".equals(nuevoEstado)) {
                                log.info("RF-04: Resolución obtenida. Estado actualizado a {}", nuevoEstado);
                                tx.setEstado(nuevoEstado);
                                tx = transaccionRepository.save(tx);

                                if ("COMPLETED".equals(nuevoEstado)) {
                                    guardarRespaldoIdempotencia(tx, "EXITO (RECUPERADO)");
                                }
                            }
                        }
                    } catch (Exception e2) {
                        log.warn("RF-04: Falló el sondeo al banco destino: {}", e2.getMessage());
                        if (tx.getFechaCreacion().plusSeconds(60)
                                .isBefore(LocalDateTime.now(java.time.ZoneOffset.UTC))) {
                            log.error("RF-04: Tiempo máximo de resolución agotado (60s). Marcando FAILED.");
                            tx.setEstado("FAILED");
                            tx = transaccionRepository.save(tx);
                        }
                    }

                } catch (Exception e) {
                    log.error("Error intentando resolver estado de tx {}", id, e);
                }
            }
        }

        return mapToDTO(tx);
    }

    public Object procesarDevolucion(ReturnRequestDTO returnRequest) {
        String originalId = returnRequest.getBody().getOriginalInstructionId();

        log.info("Procesando solicitud de devolución para instrucción original: {}", originalId);
        String returnId = returnRequest.getHeader().getMessageId();

        String redisKey = "idem:return:" + returnId;
        String fingerprint = returnId + originalId + returnRequest.getBody().getReturnAmount().getValue();
        String fingerprintMd5 = generarMD5(fingerprint);
        String redisValue = fingerprintMd5 + "|PROCESSING|-";

        Boolean claimed;
        try {
            claimed = redisTemplate.opsForValue().setIfAbsent(redisKey, redisValue, java.time.Duration.ofHours(24));
        } catch (Exception e) {
            log.error(
                    "Fallo Redis SET (Return): {}. Asumiendo nuevo por seguridad (o fallback DB si existiera tabla de returns).",
                    e.getMessage());
            claimed = true;
        }

        if (Boolean.FALSE.equals(claimed)) {
            log.warn("Duplicado detectado en Return (Redis Hit) — Replay {}", returnId);
            return "RETURN_ALREADY_PROCESSED";
        }

        String urlDevolucionCreate = devolucionUrl + "/api/v1/devoluciones";
        UUID returnUuid = UUID.fromString(returnId.replace("RET-", "").replace("MSG-", ""));
        try {
            returnUuid = UUID.fromString(returnId);
        } catch (IllegalArgumentException e) {
            returnUuid = UUID.randomUUID();
        }

        try {
            java.util.Map<String, Object> reqDev = new java.util.HashMap<>();
            reqDev.put("id", returnUuid);
            reqDev.put("idInstruccionOriginal", UUID.fromString(originalId));
            reqDev.put("codigoMotivo", returnRequest.getBody().getReturnReason()); // ej AC04
            reqDev.put("estado", "RECEIVED");

            restTemplate.postForEntity(urlDevolucionCreate, reqDev, Object.class);
            log.info("MS-Devoluciones: Solicitud registrada correctamente id={}", returnUuid);

        } catch (HttpClientErrorException e) {
            log.error("MS-Devoluciones rechazó el registro (Motivo Inválido?): {}", e.getResponseBodyAsString());
            throw new BusinessException(
                    "Error de validación legal del motivo (MS-Devolucion): " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Error contactando MS-Devoluciones: {}", e.getMessage());
            throw new BusinessException("Servicio de Auditoría de Devoluciones no disponible.");
        }

        String urlLedger = contabilidadUrl + "/api/v1/ledger/v2/switch/transfers/return";
        Object responseLedger;
        String estadoFinalDevolucion = "FAILED";

        try {
            responseLedger = restTemplate.postForObject(urlLedger, returnRequest, Object.class);
            log.info("Contabilidad: Reverso financiero EXITOSO para {}", originalId);
            estadoFinalDevolucion = "REVERSED";
        } catch (HttpClientErrorException e) {
            log.error("Contabilidad rechazó el reverso: {}", e.getResponseBodyAsString());
            actualizarEstadoDevolucion(returnUuid, "FAILED");
            throw new BusinessException("Rechazo de Contabilidad: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Error de comunicación con Contabilidad: {}", e.getMessage());
            actualizarEstadoDevolucion(returnUuid, "FAILED");
            throw new BusinessException("Error de comunicación con Contabilidad: " + e.getMessage());
        }

        actualizarEstadoDevolucion(returnUuid, estadoFinalDevolucion);

        Transaccion originalTx = transaccionRepository.findById(UUID.fromString(originalId))
                .orElseThrow(() -> new BusinessException("Transacción original no encontrada en Switch"));

        originalTx.setEstado("REVERSED");
        transaccionRepository.save(originalTx);

        log.info("Compensación: Registrando reverso en ciclo ABIERTO");
        try {
            notificarCompensacion(originalTx.getCodigoBicOrigen(), originalTx.getMonto(), false);
            notificarCompensacion(originalTx.getCodigoBicDestino(), originalTx.getMonto(), true);
        } catch (Exception e) {
            log.error("Error al compensar reverso (No bloqueante): {}", e.getMessage());
        }

        try {
            InstitucionDTO bancoOrigen = validarBanco(originalTx.getCodigoBicOrigen(), true);
            String urlWebhook = bancoOrigen.getUrlDestino() + "/api/incoming/return";

            restTemplate.postForEntity(urlWebhook, returnRequest, String.class);
            log.info("Notificación enviada al Banco Origen: {}", bancoOrigen.getNombre());
        } catch (Exception e) {
            log.warn("No se pudo notificar al Banco Origen del reverso: {}", e.getMessage());
        }

        return responseLedger;
    }

    private InstitucionDTO validarBanco(String bic, boolean permitirSoloRecibir) {
        try {
            String url = directorioUrl + "/api/v1/instituciones/" + bic;
            InstitucionDTO banco = restTemplate.getForObject(url, InstitucionDTO.class);

            if (banco == null)
                throw new BusinessException("Banco no encontrado: " + bic);

            if ("SUSPENDIDO".equalsIgnoreCase(banco.getEstadoOperativo())
                    || "MANT".equalsIgnoreCase(banco.getEstadoOperativo())) {
                throw new BusinessException("El banco " + bic + " se encuentra en MANTENIMIENTO/SUSPENDIDO.");
            }
            if ("SOLO_RECIBIR".equalsIgnoreCase(banco.getEstadoOperativo()) && !permitirSoloRecibir) {
                throw new BusinessException(
                        "El banco " + bic + " está en modo SOLO_RECIBIR y no puede iniciar transferencias.");
            }

            return banco;
        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException("El banco " + bic + " no existe.");
        } catch (Exception e) {
            if (e instanceof BusinessException)
                throw (BusinessException) e;
            throw new BusinessException("Error validando banco " + bic + ": " + e.getMessage());
        }
    }

    private void validarEnrutamientoBin(String bin, String bicDestinoEsperado) {
        try {
            String urlLookup = directorioUrl + "/api/v1/lookup/" + bin;
            InstitucionDTO bancoPropietario = restTemplate.getForObject(urlLookup, InstitucionDTO.class);

            if (bancoPropietario == null) {
                throw new BusinessException(
                        "BE01 - Routing Error: Cuenta destino desconocida (BIN " + bin + " no registrado)");
            }

            if (!bancoPropietario.getCodigoBic().equals(bicDestinoEsperado)) {
                log.error("Routing Mismatch: Cuenta {} pertenece a {} pero mensaje dirigido a {}", bin,
                        bancoPropietario.getCodigoBic(), bicDestinoEsperado);
                throw new BusinessException("BE01 - Routing Error: La cuenta destino no pertenece al banco indicado.");
            }

        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(
                    "BE01 - Routing Error: Cuenta destino desconocida (BIN " + bin + " no registrado)");
        } catch (Exception e) {
            if (e instanceof BusinessException)
                throw (BusinessException) e;
            log.warn("Error validando BIN (Non-blocking warning or blocking depending on strictness): {}",
                    e.getMessage());
            throw new BusinessException("Error Técnico Validando Enrutamiento: " + e.getMessage());
        }
    }

    private void registrarMovimientoContable(String bic, UUID idTx, BigDecimal monto, String tipo) {
        try {
            RegistroMovimientoRequest req = RegistroMovimientoRequest.builder()
                    .codigoBic(bic)
                    .idInstruccion(idTx)
                    .monto(monto)
                    .tipo(tipo)
                    .build();

            String url = contabilidadUrl + "/api/v1/ledger/movimientos";
            restTemplate.postForEntity(url, req, Object.class);

        } catch (HttpClientErrorException.BadRequest e) {
            throw new BusinessException("Error contable para " + bic + ": Fondos insuficientes o integridad violada.");
        } catch (Exception e) {
            throw new BusinessException("Error crítico con Contabilidad: " + e.getMessage());
        }
    }

    private void notificarCompensacion(String bic, BigDecimal monto, boolean esDebito) {
        try {
            String url = String.format("%s/api/v1/compensacion/acumular?bic=%s&monto=%s&esDebito=%s",
                    compensacionUrl, bic, monto.toString(), esDebito);

            restTemplate.postForEntity(url, null, Void.class);

        } catch (Exception e) {
            log.error("ALERTA: Fallo al registrar compensación para {}. Descuadre en Clearing.", bic, e);
        }
    }

    private void guardarRespaldoIdempotencia(Transaccion tx, String resultado) {
        RespaldoIdempotencia respaldo = new RespaldoIdempotencia();
        respaldo.setHashContenido("HASH_" + tx.getIdInstruccion());
        respaldo.setCuerpoRespuesta("{ \"estado\": \"" + resultado + "\" }");
        respaldo.setFechaExpiracion(LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(1));
        respaldo.setTransaccion(tx);
        idempotenciaRepository.save(respaldo);
    }

    private TransaccionResponseDTO mapToDTO(Transaccion tx) {
        return TransaccionResponseDTO.builder()
                .idInstruccion(tx.getIdInstruccion())
                .idMensaje(tx.getIdMensaje())
                .referenciaRed(tx.getReferenciaRed())
                .monto(tx.getMonto())
                .moneda(tx.getMoneda())
                .codigoBicOrigen(tx.getCodigoBicOrigen())
                .codigoBicDestino(tx.getCodigoBicDestino())
                .estado(tx.getEstado())
                .fechaCreacion(tx.getFechaCreacion())
                .build();
    }

    public List<Transaccion> listarUltimasTransacciones() {
        return transaccionRepository.findAll(PageRequest.of(0, 50, Sort.by("fechaCreacion").descending())).getContent();
    }

    private void reportarFalloAlDirectorio(String bic, String tipoFallo) {
        try {
            String urlReporte = directorioUrl + "/api/v1/instituciones/" + bic + "/reportar-fallo";
            restTemplate.postForLocation(urlReporte, null);
        } catch (Exception e) {
            log.warn("No se pudo reportar fallo al Directorio para {}: {}", bic, e.getMessage());
        }
    }

    private void ejecutarReversoSaga(Transaccion tx) {
        try {
            log.warn("SAGA COMPENSACIÓN: Iniciando reverso local para Tx {}", tx.getIdInstruccion());
            registrarMovimientoContable(tx.getCodigoBicOrigen(), tx.getIdInstruccion(), tx.getMonto(), "CREDIT");
            registrarMovimientoContable(tx.getCodigoBicDestino(), tx.getIdInstruccion(), tx.getMonto(), "DEBIT");
            notificarCompensacion(tx.getCodigoBicOrigen(), tx.getMonto(), false);
            notificarCompensacion(tx.getCodigoBicDestino(), tx.getMonto(), true);
            log.info("SAGA COMPENSACIÓN: Reverso completado exitosamente.");
        } catch (Exception e) {
            log.error("CRITICAL: Fallo en Saga de Reverso. Inconsistencia Contable posible. {}", e.getMessage());
        }
    }

    public List<Transaccion> buscarTransacciones(String id, String bic, String estado) {
        return transaccionRepository.buscarTransacciones(
                (id != null && !id.isBlank()) ? id : null,
                (bic != null && !bic.isBlank()) ? bic : null,
                (estado != null && !estado.isBlank()) ? estado : null);
    }

    public java.util.Map<String, Object> obtenerEstadisticas() {
        LocalDateTime start = LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(24);

        long total = transaccionRepository.countTransaccionesDesde(start);
        BigDecimal volumen = transaccionRepository.sumMontoExitosoDesde(start);
        List<Object[]> porEstado = transaccionRepository.countPorEstadoDesde(start);

        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalTransactions24h", total);
        stats.put("totalVolumeExample", volumen != null ? volumen : BigDecimal.ZERO);

        long exitosas = 0;
        for (Object[] row : porEstado) {
            String status = (String) row[0];
            long count = (Long) row[1];
            if ("COMPLETED".equals(status)) {
                exitosas = count;
            }
            stats.put("count_" + status, count);
        }

        double tasaExito = (total > 0) ? ((double) exitosas / total) * 100 : 0.0;
        stats.put("successRate", Math.round(tasaExito * 100.0) / 100.0);

        double tps = (total > 0) ? (double) total / (24 * 3600) : 0.0;
        stats.put("tps", Math.round(tps * 1000.0) / 1000.0);

        return stats;
    }

    private void actualizarEstadoDevolucion(UUID id, String estado) {
        try {
            try {
                restTemplate.put(devolucionUrl + "/api/v1/devoluciones/" + id + "/estado?estado=" + estado, null);
            } catch (Exception ex) {
                log.warn("Fallo actualizacion estado devolucion: " + ex.getMessage());
            }

            log.info("MS-Devolucion: Intento de actualizar estado a {} para {}", estado, id);

        } catch (Exception e) {
            log.warn("No se pudo actualizar el estado de la devolución en auditoría: {}", e.getMessage());
        }
    }

    private String generarMD5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error generando MD5", e);
        }
    }

}