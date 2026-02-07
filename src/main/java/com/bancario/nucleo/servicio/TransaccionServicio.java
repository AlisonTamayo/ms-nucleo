package com.bancario.nucleo.servicio;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import com.bancario.nucleo.repositorio.TransaccionRepositorio;
import com.bancario.nucleo.repositorio.RespaldoIdempotenciaRepositorio;
import com.bancario.nucleo.modelo.Transaccion;
import com.bancario.nucleo.modelo.RespaldoIdempotencia;
import com.bancario.nucleo.modelo.IsoError;
import com.bancario.nucleo.mapper.TransaccionMapper;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;

import com.bancario.nucleo.dto.TransaccionResponseDTO;
import com.bancario.nucleo.dto.ReturnRequestDTO;
import com.bancario.nucleo.dto.external.InstitucionDTO;
import com.bancario.nucleo.dto.external.RegistroMovimientoRequest;
import com.bancario.nucleo.dto.iso.MensajeISO;
import com.bancario.nucleo.excepcion.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransaccionServicio {

    private final StringRedisTemplate redisTemplate;
    private final TransaccionRepositorio transaccionRepositorio;
    private final RespaldoIdempotenciaRepositorio idempotenciaRepositorio;
    private final RestTemplate restTemplate;
    private final io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry;
    private final TransaccionMapper transaccionMapper;
    private final NormalizadorErroresServicio normalizadorErrores;

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
        log.info(">>> ESCUDO ROBUSTO ACTIVADO v2 <<<");
        UUID idInstruccion;
        String bicOrigen, bicDestino, moneda, messageId, creationDateTime, cuentaOrigen, cuentaDestino;
        BigDecimal monto;
        String fingerprintMd5;
        boolean debitRealizado = false;

        try {
            if (iso.getBody() == null || iso.getHeader() == null) {
                throw new BusinessException(IsoError.RC01.getCodigo() + " - " + IsoError.RC01.getDescripcion()
                        + ": Header o Body son nulos.");
            }

            String rawId = iso.getBody().getInstructionId();
            idInstruccion = parseUuidSeguro(rawId);

            bicOrigen = iso.getHeader().getOriginatingBankId();
            bicDestino = iso.getBody().getCreditor().getTargetBankId();
            monto = iso.getBody().getAmount().getValue();
            moneda = iso.getBody().getAmount().getCurrency();
            messageId = iso.getHeader().getMessageId();
            creationDateTime = iso.getHeader().getCreationDateTime();
            cuentaOrigen = iso.getBody().getDebtor().getAccountId();
            cuentaDestino = iso.getBody().getCreditor().getAccountId();

            if (!"USD".equalsIgnoreCase(moneda)) {
                throw new BusinessException(IsoError.AC03.getCodigo() + " - Moneda no soportada: " + moneda);
            }
            if (monto.compareTo(new BigDecimal("10000")) > 0) {
                throw new BusinessException(
                        IsoError.CH03.getCodigo() + " - Monto excede el límite permitido (Max: 10,000 USD)");
            }

            String fingerprint = idInstruccion.toString() + monto.toString() + moneda + bicOrigen + bicDestino
                    + creationDateTime + cuentaOrigen + cuentaDestino;
            fingerprintMd5 = generarMD5(fingerprint);
        } catch (NullPointerException e) {
            log.error("Error: Datos obligatorios faltantes en el mensaje ISO.", e);
            throw new BusinessException(
                    IsoError.RC01.getCodigo() + " - Datos obligatorios faltantes en el mensaje ISO (NPE).");
        } catch (Exception e) {
            log.error("Error inesperado procesando datos iniciales ISO: {}", e.getMessage());
            throw new BusinessException(
                    IsoError.MS03.getCodigo() + " - Error leyendo datos del mensaje: " + e.getMessage());
        }

        log.info(">>> Iniciando Tx ISO: InstID={} MsgID={} Monto={}", idInstruccion, messageId, monto);

        String redisKey = "idem:" + idInstruccion;
        String redisValue = fingerprintMd5 + "|PROCESSING|-";

        Boolean claimed;
        try {
            claimed = redisTemplate.opsForValue().setIfAbsent(redisKey, redisValue, java.time.Duration.ofHours(24));
        } catch (Exception e) {
            log.error("Fallo Redis SET: {}. Pasando a Fallback DB.", e.getMessage());
            boolean existeEnRespaldo = idempotenciaRepositorio.findByHashContenido("HASH_" + idInstruccion).isPresent();
            boolean existeEnTx = transaccionRepositorio.existsById(idInstruccion);

            claimed = !(existeEnRespaldo || existeEnTx);
        }

        if (Boolean.TRUE.equals(claimed)) {
            log.info("Idempotencia: Nueva transacción detectada (Redis Claim). Procesando...");
            log.info("Redis CLAIM OK (o Fallback DB) — Nueva transacción {}", idInstruccion);
        } else {
            String existingVal = null;
            try {
                existingVal = redisTemplate.opsForValue().get(redisKey);
            } catch (Exception e) {
                log.warn("Redis GET falló tras saber que es duplicado. Intentando recuperar detalles de DB...");
            }

            if (existingVal == null) {
                return idempotenciaRepositorio.findByHashContenido("HASH_" + idInstruccion)
                        .map(respaldo -> {
                            log.warn("Duplicado detectado via DB-Backup (Redis Offline). Retornando original para {}",
                                    idInstruccion);
                            return transaccionMapper.toDTO(respaldo.getTransaccion());
                        })
                        .orElseGet(() -> {
                            return transaccionRepositorio.findById(idInstruccion)
                                    .map(txEncurso -> {
                                        log.warn(
                                                "Duplicado detectado via DB-Tx (Redis Offline). Transacción en curso o sin respaldo: {}",
                                                idInstruccion);
                                        return transaccionMapper.toDTO(txEncurso);
                                    })
                                    .orElseThrow(() -> new IllegalStateException(
                                            "Inconsistencia: Detectado como duplicado pero no encontrado en DB ni Redis."));
                        });
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

        tx = transaccionRepositorio.save(tx);

        try {
            String bin = (cuentaDestino != null && cuentaDestino.length() >= 6) ? cuentaDestino.substring(0, 6)
                    : "000000";
            validarEnrutamientoBin(bin, bicDestino);
            log.info("Enrutamiento: BIN {} mapeado correctamente a {}", bin, bicDestino);

            validarBanco(bicOrigen, false);
            InstitucionDTO bancoDestinoInfo = validarBanco(bicDestino, true);
            log.info("Validación: Bancos Origen ({}) y Destino ({}) operativos.", bicOrigen, bicDestino);

            log.info("Validación: Bancos Origen ({}) y Destino ({}) operativos.", bicOrigen, bicDestino);

            log.info("Ledger: Reservando fondos (Pre-Autorización) a {}", bicOrigen);
            reservarBalance(bicOrigen, idInstruccion, monto);
            debitRealizado = true; // Flag now indicates "Reservation Made"

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
                            HttpHeaders headers = new HttpHeaders();
                            headers.setContentType(MediaType.APPLICATION_JSON);
                            if (bancoDestinoInfo.getLlavePublica() != null) {
                                headers.set("apikey", bancoDestinoInfo.getLlavePublica());
                            }

                            HttpEntity<MensajeISO> request = new HttpEntity<>(iso, headers);
                            restTemplate.postForEntity(urlWebhook, request, String.class);
                        });

                        log.info("Webhook: Entregado exitosamente.");

                        // NO MORE DIRECT CREDIT TO DESTINATION (Deferred)

                        log.info("Clearing: Registrando operación PAGO en ciclo abierto");
                        registrarOperacionCompensacion(bicOrigen, bicDestino, idInstruccion, monto, "PAGO");

                        entregado = true;
                        log.info("Entrega: CONFIRMADA al Banco Destino. Finalizando Tx.");
                        break;

                    } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
                        log.error("CIRCUIT BREAKER ABIERTO para {}. Transaccion rechazada inmediatamente.", bicDestino);
                        throw new BusinessException(IsoError.MS03.getCodigo() + " - El Banco Destino " + bicDestino
                                + " está NO DISPONIBLE (Circuit Breaker Activo). Intente más tarde.");
                    }
                } catch (BusinessException e) {
                    throw e;
                } catch (HttpClientErrorException e) {
                    String errorBody = e.getResponseBodyAsString();
                    String isoCode = normalizadorErrores.normalizarError(errorBody);
                    log.error("Rechazo Normalizado del Banco Destino: {} -> {}", errorBody, isoCode);

                    throw new BusinessException(isoCode + " - Transacción Rechazada por Banco Destino: " + errorBody);

                } catch (org.springframework.web.client.HttpServerErrorException e) {
                    log.error("Error 5xx del Banco Destino: {}", e.getStatusCode());
                    reportarFalloAlDirectorio(bicDestino, "HTTP_5XX");

                    String isoCode = IsoError.MS03.getCodigo();
                    throw new BusinessException(
                            isoCode + " - Banco Destino falló internamente (HTTP 5xx). Código: " + e.getStatusCode());

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
                transaccionRepositorio.save(tx);

                throw new java.util.concurrent.TimeoutException("No se obtuvo respuesta del Banco Destino");
            }

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("Transacción en estado PENDING por Timeout: {}", e.getMessage());
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "Tiempo de espera agotado con Banco Destino");

        } catch (BusinessException e) {
            log.error("Error de Negocio: {}", e.getMessage());
            if (debitRealizado) {
                ejecutarReversoSaga(tx);
            }
            tx.setEstado("FAILED");
        } catch (Exception e) {
            log.error("Error crítico en Tx: {}", e.getMessage());
            if (debitRealizado) {
                ejecutarReversoSaga(tx);
            }
            tx.setEstado("FAILED");
        }

        Transaccion saved = transaccionRepositorio.save(tx);
        return transaccionMapper.toDTO(saved);
    }

    public TransaccionResponseDTO obtenerTransaccion(UUID id) {
        Transaccion tx = transaccionRepositorio.findById(id)
                .orElseThrow(() -> new BusinessException(
                        IsoError.RC01.getCodigo() + " - Transacción no encontrada con ID: " + id));

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
                                tx = transaccionRepositorio.save(tx);

                                if ("COMPLETED".equals(nuevoEstado)) {
                                    try {
                                        registrarMovimientoContable(tx.getCodigoBicDestino(), tx.getIdInstruccion(),
                                                tx.getMonto(), "CREDIT");
                                        notificarCompensacion(tx.getCodigoBicDestino(), tx.getMonto(), false);
                                    } catch (Exception ex) {
                                        log.error("Error aplicando contabilidad en Recuperación: {}", ex.getMessage());
                                    }
                                    guardarRespaldoIdempotencia(tx, "EXITO (RECUPERADO)");
                                } else if ("FAILED".equals(nuevoEstado)) {
                                    ejecutarReversoSaga(tx);
                                }
                            }
                        }
                    } catch (Exception e2) {
                        log.warn("RF-04: Falló el sondeo al banco destino: {}", e2.getMessage());
                        if (tx.getFechaCreacion().plusSeconds(60)
                                .isBefore(LocalDateTime.now(java.time.ZoneOffset.UTC))) {
                            log.error("RF-04: Tiempo máximo de resolución agotado (60s). Marcando FAILED.");
                            tx.setEstado("FAILED");
                            tx = transaccionRepositorio.save(tx);
                            ejecutarReversoSaga(tx);
                        }
                    }

                } catch (Exception e) {
                    log.error("Error intentando resolver estado de tx {}", id, e);
                }
            }
        }

        return transaccionMapper.toDTO(tx);
    }

    public Object procesarDevolucion(ReturnRequestDTO returnRequest) {
        String originalId = returnRequest.getBody().getOriginalInstructionId();

        if (originalId == null || originalId.isBlank()) {
            throw new BusinessException(
                    IsoError.RC01.getCodigo() + " - El campo 'originalInstructionId' es obligatorio.");
        }

        try {
            UUID.fromString(originalId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(IsoError.RC01.getCodigo()
                    + " - El 'originalInstructionId' no tiene un formato UUID válido: " + originalId);
        }

        Transaccion originalTxCheck = transaccionRepositorio.findById(UUID.fromString(originalId))
                .orElseThrow(() -> new BusinessException(IsoError.RC01.getCodigo()
                        + " - Transacción original no encontrada en Switch (ID=" + originalId + ")"));

        if ("REVERSED".equals(originalTxCheck.getEstado())) {
            log.warn("RF-03 Idempotencia: Transacción {} ya está REVERSED. Retornando éxito sin reprocesar.",
                    originalId);
            Map<String, String> response = new java.util.HashMap<>();
            response.put("status", "ALREADY_REVERSED");
            response.put("message", "La transacción ya fue reversada previamente.");
            return response;
        }

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
            log.error("Fallo Redis SET (Return): {}. Fallback a DB.", e.getMessage());
            claimed = true;
        }

        if (Boolean.FALSE.equals(claimed)) {
            log.warn("Duplicado detectado en Return (Redis Hit) — Replay {}", returnId);
            return "RETURN_ALREADY_PROCESSED";
        }

        if (originalId.equals(returnId.replace("RET-", "").replace("MSG-", "")) || originalId.equals(returnId)) {
            String safeUuid = UUID.randomUUID().toString();
            log.info("Sanitizando ID de Retorno: {} -> {}", returnId, safeUuid);

            returnId = safeUuid;
            returnRequest.getBody().setReturnInstructionId(safeUuid);
            returnRequest.getHeader().setMessageId("RET-" + safeUuid);
        }

        String urlDevolucionCreate = devolucionUrl + "/api/v1/devoluciones";
        UUID returnUuid;
        try {
            returnUuid = UUID.fromString(returnId.replace("RET-", "").replace("MSG-", ""));
        } catch (IllegalArgumentException e) {
            returnUuid = UUID.randomUUID();
        }

        try {
            Map<String, Object> reqDev = new java.util.HashMap<>();
            reqDev.put("id", returnUuid);
            reqDev.put("idInstruccionOriginal", originalId);
            reqDev.put("codigoMotivo", returnRequest.getBody().getReturnReason());
            reqDev.put("estado", "RECEIVED");

            restTemplate.postForEntity(urlDevolucionCreate, reqDev, Object.class);
            log.info("MS-Devoluciones: Solicitud registrada correctamente id={}", returnUuid);

        } catch (HttpClientErrorException e) {
            log.error("MS-Devoluciones rechazó el registro (Motivo Inválido?): {}", e.getResponseBodyAsString());
            throw new BusinessException(
                    IsoError.RC01.getCodigo() + " - Error de validación legal del motivo (MS-Devolucion): "
                            + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Error contactando MS-Devoluciones: {}", e.getMessage());
            throw new BusinessException(
                    IsoError.MS03.getCodigo() + " - Servicio de Auditoría de Devoluciones no disponible.");
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
            throw new BusinessException(
                    IsoError.AG01.getCodigo() + " - Rechazo de Contabilidad: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Error de comunicación con Contabilidad: {}", e.getMessage());
            actualizarEstadoDevolucion(returnUuid, "FAILED");
            throw new BusinessException(
                    IsoError.MS03.getCodigo() + " - Error de comunicación con Contabilidad: " + e.getMessage());
        }

        actualizarEstadoDevolucion(returnUuid, estadoFinalDevolucion);

        Transaccion originalTx = transaccionRepositorio.findById(UUID.fromString(originalId))
                .orElseThrow(() -> new BusinessException(
                        IsoError.RC01.getCodigo() + " - Transacción original no encontrada (Post-Validación)"));

        originalTx.setEstado("REVERSED");
        transaccionRepositorio.save(originalTx);

        log.info("Compensación: Registrando reverso en ciclo ABIERTO");
        try {
            notificarCompensacion(originalTx.getCodigoBicOrigen(), originalTx.getMonto(), false);
            notificarCompensacion(originalTx.getCodigoBicDestino(), originalTx.getMonto(), true);
        } catch (Exception e) {
            log.error("Error al compensar reverso (No bloqueante): {}", e.getMessage());
        }

        try {
            InstitucionDTO bancoOrigen = validarBanco(originalTx.getCodigoBicOrigen(), true);
            String urlWebhook = bancoOrigen.getUrlDestino();
            if (!urlWebhook.endsWith("/recepcion")) {
                urlWebhook += "/api/incoming/return";
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (bancoOrigen.getLlavePublica() != null) {
                headers.set("apikey", bancoOrigen.getLlavePublica());
            }

            HttpEntity<ReturnRequestDTO> request = new HttpEntity<>(returnRequest, headers);
            restTemplate.postForEntity(urlWebhook, request, String.class);
            log.info("Notificación enviada al Banco Origen: {}", bancoOrigen.getNombre());
        } catch (Exception e) {
            log.warn("No se pudo notificar al Banco Origen del reverso: {}", e.getMessage());
        }

        try {
            InstitucionDTO bancoDestino = validarBanco(originalTx.getCodigoBicDestino(), true);
            String urlWebhookDestino = bancoDestino.getUrlDestino();
            if (!urlWebhookDestino.endsWith("/recepcion")) {
                urlWebhookDestino += "/api/incoming/return";
            }

            HttpHeaders headersDestino = new HttpHeaders();
            headersDestino.setContentType(MediaType.APPLICATION_JSON);
            if (bancoDestino.getLlavePublica() != null) {
                headersDestino.set("apikey", bancoDestino.getLlavePublica());
            }

            HttpEntity<ReturnRequestDTO> requestDestino = new HttpEntity<>(returnRequest, headersDestino);
            restTemplate.postForEntity(urlWebhookDestino, requestDestino, String.class);
            log.info("Notificación de REVERSO enviada al Banco Destino (Para débito): {}", bancoDestino.getNombre());

        } catch (Exception e) {
            log.error(
                    "CRÍTICO: No se pudo notificar al Banco Destino del reverso. Descuadre potencial en saldos clientes: {}",
                    e.getMessage());
        }

        registrarTransaccionDeRetorno(returnRequest, returnUuid,
                "REVERSED".equals(estadoFinalDevolucion) ? "COMPLETED" : "FAILED");
        return responseLedger;
    }

    private void registrarTransaccionDeRetorno(ReturnRequestDTO returnRequest, UUID returnUuid, String estado) {
        try {
            Transaccion tx = new Transaccion();
            tx.setIdInstruccion(returnUuid);
            tx.setIdMensaje(returnRequest.getHeader().getMessageId());

            String bicOrigen = returnRequest.getHeader().getOriginatingBankId();

            String originalId = returnRequest.getBody().getOriginalInstructionId();
            transaccionRepositorio.findById(UUID.fromString(originalId)).ifPresent(original -> {
                tx.setCodigoBicDestino(original.getCodigoBicOrigen());
            });
            if (tx.getCodigoBicDestino() == null) {
                tx.setCodigoBicDestino("UNKNOWN");
            }

            tx.setCodigoBicOrigen(bicOrigen);
            tx.setMonto(returnRequest.getBody().getReturnAmount().getValue());
            tx.setMoneda(returnRequest.getBody().getReturnAmount().getCurrency());

            String rawRef = tx.getMonto().toString() + bicOrigen + tx.getIdMensaje() + LocalDateTime.now();
            tx.setReferenciaRed(generarMD5(rawRef).toUpperCase());

            tx.setEstado(estado);
            tx.setFechaCreacion(LocalDateTime.now(java.time.ZoneOffset.UTC));

            transaccionRepositorio.save(tx);
            log.info("Refund registrado como Transacción: {}", returnUuid);
        } catch (Exception e) {
            log.warn("No se pudo registrar la transacción de Refund para visualización: {}", e.getMessage());
        }
    }

    private InstitucionDTO validarBanco(String bic, boolean permitirSoloRecibir) {
        try {
            String url = directorioUrl + "/api/v1/instituciones/" + bic;
            InstitucionDTO banco = restTemplate.getForObject(url, InstitucionDTO.class);

            if (banco == null)
                throw new BusinessException(IsoError.AC01.getCodigo() + " - Banco no encontrado en Directorio: " + bic);

            if ("SUSPENDIDO".equalsIgnoreCase(banco.getEstadoOperativo())
                    || "MANT".equalsIgnoreCase(banco.getEstadoOperativo())
                    || "OFFLINE".equalsIgnoreCase(banco.getEstadoOperativo())) {
                throw new BusinessException(IsoError.MS03.getCodigo() + " - El banco " + bic
                        + " se encuentra en NO DISPONIBLE (Mantenimiento/Offline).");
            }
            if ("SOLO_RECIBIR".equalsIgnoreCase(banco.getEstadoOperativo()) && !permitirSoloRecibir) {
                throw new BusinessException(IsoError.AG01.getCodigo() + " - El banco " + bic
                        + " está en modo SOLO_RECIBIR y no puede iniciar transferencias.");
            }

            return banco;
        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(
                    IsoError.AC01.getCodigo() + " - El banco " + bic + " no existe o no está registrado.");
        } catch (Exception e) {
            if (e instanceof BusinessException)
                throw (BusinessException) e;
            throw new BusinessException(
                    IsoError.MS03.getCodigo() + " - Error técnico validando banco " + bic + ": " + e.getMessage());
        }
    }

    private void validarEnrutamientoBin(String bin, String bicDestinoEsperado) {
        try {
            String urlLookup = directorioUrl + "/api/v1/lookup/" + bin;
            InstitucionDTO bancoPropietario = restTemplate.getForObject(urlLookup, InstitucionDTO.class);

            if (bancoPropietario == null) {
                throw new BusinessException(
                        IsoError.BE01.getCodigo() + " - Routing Error: Cuenta destino desconocida (BIN " + bin
                                + " no registrado)");
            }

            if (!bancoPropietario.getCodigoBic().equals(bicDestinoEsperado)) {
                log.error("Routing Mismatch: Cuenta {} pertenece a {} pero mensaje dirigido a {}", bin,
                        bancoPropietario.getCodigoBic(), bicDestinoEsperado);
                throw new BusinessException(IsoError.BE01.getCodigo()
                        + " - Routing Error: La cuenta destino no pertenece al banco indicado.");
            }

        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(
                    IsoError.BE01.getCodigo() + " - Routing Error: Cuenta destino desconocida (BIN " + bin
                            + " no registrado)");
        } catch (Exception e) {
            if (e instanceof BusinessException)
                throw (BusinessException) e;
            log.warn("Error validando BIN (Non-blocking warning or blocking depending on strictness): {}",
                    e.getMessage());
            throw new BusinessException(
                    IsoError.MS03.getCodigo() + " - Error Técnico Validando Enrutamiento: " + e.getMessage());
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
            throw new BusinessException(IsoError.AM04.getCodigo() + " - Error contable para " + bic
                    + ": Fondos insuficientes o integridad violada.");
        } catch (Exception e) {
            throw new BusinessException(
                    IsoError.MS03.getCodigo() + " - Error crítico con Contabilidad: " + e.getMessage());
        }
    }

    private void reservarBalance(String bic, UUID idTx, BigDecimal monto) {
        try {
            RegistroMovimientoRequest req = RegistroMovimientoRequest.builder()
                    .codigoBic(bic)
                    .idInstruccion(idTx)
                    .monto(monto)
                    .tipo("DEBIT") // Logical type for check
                    .build();

            String url = contabilidadUrl + "/api/v1/ledger/reservar";
            restTemplate.postForEntity(url, req, Object.class);

        } catch (HttpClientErrorException.BadRequest e) {
            throw new BusinessException(IsoError.AM04.getCodigo() + " - Fondos insuficientes para reservar.");
        } catch (Exception e) {
            throw new BusinessException(
                    IsoError.MS03.getCodigo() + " - Error crítico reservando fondos: " + e.getMessage());
        }
    }

    private void registrarOperacionCompensacion(String bicEmisor, String bicReceptor, UUID idTx, BigDecimal monto,
            String tipo) {
        try {
            com.bancario.nucleo.dto.external.RegistroOperacionDTO req = com.bancario.nucleo.dto.external.RegistroOperacionDTO
                    .builder()
                    .idInstruccion(idTx)
                    .bicEmisor(bicEmisor)
                    .bicReceptor(bicReceptor)
                    .monto(monto)
                    .tipoOperacion(tipo)
                    .build();

            String url = compensacionUrl + "/api/v1/compensacion/operaciones";
            restTemplate.postForEntity(url, req, Void.class);

        } catch (Exception e) {
            log.error("ALERTA: Fallo al registrar operación en Clearing ({}) para {}. Descuadre posible.", tipo,
                    bicEmisor, e);
        }
    }

    private void guardarRespaldoIdempotencia(Transaccion tx, String resultado) {
        RespaldoIdempotencia respaldo = new RespaldoIdempotencia();
        respaldo.setHashContenido("HASH_" + tx.getIdInstruccion());
        respaldo.setCuerpoRespuesta("{ \"estado\": \"" + resultado + "\" }");
        respaldo.setFechaExpiracion(LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(1));
        respaldo.setTransaccion(tx);
        idempotenciaRepositorio.save(respaldo);
    }

    public List<TransaccionResponseDTO> listarUltimasTransacciones() {
        List<Transaccion> txs = transaccionRepositorio
                .findAll(PageRequest.of(0, 50, Sort.by("fechaCreacion").descending())).getContent();
        return transaccionMapper.toDTOList(txs);
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
            log.warn("SAGA COMPENSACIÓN: Iniciando reverso local (release blocks) para Tx {}", tx.getIdInstruccion());

            // 1. Register PAGO (So 'Total Debits' includes this, releasing the block in
            // closing)
            registrarOperacionCompensacion(tx.getCodigoBicOrigen(), tx.getCodigoBicDestino(), tx.getIdInstruccion(),
                    tx.getMonto(), "PAGO");

            // 2. Register REVERSO (So Net Position cancels out, refunding the user in
            // 'Available')
            registrarOperacionCompensacion(tx.getCodigoBicOrigen(), tx.getCodigoBicDestino(), tx.getIdInstruccion(),
                    tx.getMonto(), "REVERSO");

            // No need to manually CREDIT ledger. Cycle Closing handles it.

            notificarReversoAlBancoOrigen(tx);

            log.info(
                    "SAGA COMPENSACIÓN: Reverso registrado en Clearing exitosamente (Funds will unblock at cycle close).");

        } catch (Exception e) {
            log.error("CRITICAL: Fallo en Saga de Reverso. Inconsistencia Contable posible. {}", e.getMessage());
        }
    }

    private void notificarReversoAlBancoOrigen(Transaccion tx) {
        try {
            InstitucionDTO bancoOrigen = validarBanco(tx.getCodigoBicOrigen(), true);
            String urlWebhook = bancoOrigen.getUrlDestino();
            if (!urlWebhook.endsWith("/recepcion")) {
                urlWebhook = urlWebhook.replace("/transferencias/recepcion", "") + "/api/incoming/return";
            } else {
                urlWebhook += "/return";
            }

            ReturnRequestDTO aviso = new ReturnRequestDTO();

            ReturnRequestDTO.Header header = new ReturnRequestDTO.Header();
            header.setMessageId("REV-" + UUID.randomUUID().toString());
            header.setCreationDateTime(LocalDateTime.now().toString());
            header.setOriginatingBankId("SWITCH");
            aviso.setHeader(header);

            ReturnRequestDTO.Body body = new ReturnRequestDTO.Body();
            body.setOriginalInstructionId(tx.getIdInstruccion().toString());
            body.setReturnReason(IsoError.MS03.getCodigo());

            ReturnRequestDTO.Amount monto = new ReturnRequestDTO.Amount();
            monto.setValue(tx.getMonto());
            monto.setCurrency(tx.getMoneda());
            body.setReturnAmount(monto);

            aviso.setBody(body);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (bancoOrigen.getLlavePublica() != null) {
                headers.set("apikey", bancoOrigen.getLlavePublica());
            }

            log.info("Notificando REVERSO AUTÓNOMO a {}: {}", bancoOrigen.getNombre(), urlWebhook);
            HttpEntity<ReturnRequestDTO> request = new HttpEntity<>(aviso, headers);

            restTemplate.postForEntity(urlWebhook, request, String.class);
            log.info("Notificación de reverso enviada OK.");

        } catch (Exception e) {
            log.warn("No se pudo notificar el reverso al Banco Origen: {}", e.getMessage());
        }
    }

    public List<TransaccionResponseDTO> buscarTransacciones(String id, String bic, String estado) {
        List<Transaccion> txs = transaccionRepositorio.buscarTransacciones(
                (id != null && !id.isBlank()) ? id : null,
                (bic != null && !bic.isBlank()) ? bic : null,
                (estado != null && !estado.isBlank()) ? estado : null);
        return transaccionMapper.toDTOList(txs);
    }

    public Map<String, Object> obtenerEstadisticas() {
        LocalDateTime start = LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(24);

        long total = transaccionRepositorio.countTransaccionesDesde(start);
        BigDecimal volumen = transaccionRepositorio.sumMontoExitosoDesde(start);
        List<Object[]> porEstado = transaccionRepositorio.countPorEstadoDesde(start);

        Map<String, Object> stats = new java.util.HashMap<>();
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

    /**
     * Método deprecado - se mantiene para compatibilidad.
     * El sistema ahora usa registrarOperacionCompensacion() para el flujo DNS.
     */
    @Deprecated
    private void notificarCompensacion(String bic, BigDecimal monto, boolean esDebito) {
        try {
            String url = compensacionUrl + "/api/v1/compensacion/acumular?bic=" + bic
                    + "&monto=" + monto + "&esDebito=" + esDebito;
            restTemplate.postForEntity(url, null, Void.class);
            log.info("Compensación notificada: BIC={} Monto={} Debito={}", bic, monto, esDebito);
        } catch (Exception e) {
            log.warn("Error notificando compensación (no bloqueante): {}", e.getMessage());
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

    private UUID parseUuidSeguro(String rawId) {
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            log.warn("ID no estándar recibido ('{}'). Generando UUID compatible.", rawId);
            return UUID.nameUUIDFromBytes(rawId.getBytes(StandardCharsets.UTF_8));
        }
    }

}
