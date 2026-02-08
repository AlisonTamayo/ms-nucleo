package com.bancario.nucleo.servicio;

import com.bancario.nucleo.dto.StatusReportDTO;
import com.bancario.nucleo.dto.TransaccionResponseDTO;
import com.bancario.nucleo.dto.external.InstitucionDTO;
import com.bancario.nucleo.excepcion.BusinessException;
import com.bancario.nucleo.modelo.IsoError;
import com.bancario.nucleo.modelo.Transaccion;
import com.bancario.nucleo.repositorio.TransaccionRepositorio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackServicio {

    private final TransaccionRepositorio transaccionRepositorio;
    private final RestTemplate restTemplate;

    @Value("${service.directorio.url:http://ms-directorio:8081}")
    private String directorioUrl;

    @Value("${service.contabilidad.url:http://ms-contabilidad:8083}")
    private String contabilidadUrl;

    @Value("${service.compensacion.url:http://ms-compensacion:8084}")
    private String compensacionUrl;

    @Transactional
    public TransaccionResponseDTO procesarCallback(StatusReportDTO statusReport) {
        UUID instructionId = statusReport.getBody().getOriginalInstructionId();
        String status = statusReport.getBody().getStatus();
        String respondingBank = statusReport.getHeader().getRespondingBankId();

        log.info("═══════════════════════════════════════════════════════════════════════════");
        log.info("CALLBACK RECIBIDO del banco: {}", respondingBank);
        log.info("  InstructionId: {}", instructionId);
        log.info("  Status: {}", status);
        log.info("═══════════════════════════════════════════════════════════════════════════");

        Transaccion tx = transaccionRepositorio.findById(instructionId)
                .orElseThrow(() -> new BusinessException(
                        IsoError.RC01.getCodigo() + " - Transacción no encontrada: " + instructionId));
        if (!"QUEUED".equals(tx.getEstado())) {
            log.warn("Callback para transacción en estado {}, esperaba QUEUED", tx.getEstado());
            throw new BusinessException(
                    "FF01 - Transacción no está en estado QUEUED. Estado actual: " + tx.getEstado());
        }

        if ("COMPLETED".equals(status)) {
            procesarExito(tx, statusReport);
        } else if ("REJECTED".equals(status)) {
            procesarRechazo(tx, statusReport);
        } else {
            throw new BusinessException("FF02 - Estado no válido: " + status + ". Use COMPLETED o REJECTED");
        }

        Transaccion saved = transaccionRepositorio.save(tx);

        notificarBancoOrigen(tx, statusReport);

        log.info("Callback procesado exitosamente. Estado final: {}", tx.getEstado());

        return TransaccionResponseDTO.builder()
                .idInstruccion(saved.getIdInstruccion())
                .idMensaje(saved.getIdMensaje())
                .monto(saved.getMonto())
                .moneda(saved.getMoneda())
                .codigoBicOrigen(saved.getCodigoBicOrigen())
                .codigoBicDestino(saved.getCodigoBicDestino())
                .estado(saved.getEstado())
                .fechaCreacion(saved.getFechaCreacion())
                .build();
    }

    private void procesarExito(Transaccion tx, StatusReportDTO statusReport) {
        log.info("Procesando ÉXITO para transacción {}", tx.getIdInstruccion());

        try {
            registrarMovimientoContable(tx.getCodigoBicDestino(), tx.getIdInstruccion(), tx.getMonto(), "CREDIT");
            log.info("Ledger: CREDIT registrado para {} por {}", tx.getCodigoBicDestino(), tx.getMonto());
        } catch (Exception e) {
            log.error("Error registrando CREDIT en Ledger: {}", e.getMessage());
            throw new BusinessException("MS03 - Error en Ledger: " + e.getMessage());
        }

        try {
            notificarCompensacion(tx.getCodigoBicDestino(), tx.getMonto(), false);
            log.info("Clearing: Posición CREDIT registrada para {}", tx.getCodigoBicDestino());
        } catch (Exception e) {
            log.warn("Error notificando a Compensación: {}", e.getMessage());
        }

        tx.setEstado("COMPLETED");
    }

    private void procesarRechazo(Transaccion tx, StatusReportDTO statusReport) {
        String reasonCode = statusReport.getBody().getReasonCode();
        String reasonDescription = statusReport.getBody().getReasonDescription();

        log.info("Procesando RECHAZO para transacción {}", tx.getIdInstruccion());
        log.info("  ReasonCode: {}, Description: {}", reasonCode, reasonDescription);

        try {
            registrarMovimientoContable(tx.getCodigoBicOrigen(), tx.getIdInstruccion(), tx.getMonto(), "CREDIT");
            log.info("Ledger: CREDIT (reverso) registrado para {} por {}", tx.getCodigoBicOrigen(), tx.getMonto());
        } catch (Exception e) {
            log.error("Error en reverso de Ledger: {}", e.getMessage());
            throw new BusinessException("MS03 - Error reversando en Ledger: " + e.getMessage());
        }

        try {
            notificarCompensacion(tx.getCodigoBicOrigen(), tx.getMonto(), false);
            log.info("Clearing: Reverso de posición registrado para {}", tx.getCodigoBicOrigen());
        } catch (Exception e) {
            log.warn("Error notificando reverso a Compensación: {}", e.getMessage());
        }

        tx.setEstado("REJECTED");
    }

    private void notificarBancoOrigen(Transaccion tx, StatusReportDTO statusReport) {
        String bicOrigen = tx.getCodigoBicOrigen();

        try {
            InstitucionDTO bancoOrigen = obtenerBancoDelDirectorio(bicOrigen);
            String webhookUrl = bancoOrigen.getUrlDestino();

            if (webhookUrl == null || webhookUrl.isBlank()) {
                log.warn("Banco origen {} no tiene webhook configurado", bicOrigen);
                return;
            }

            log.info("Notificando al banco origen {} vía webhook: {}", bicOrigen, webhookUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (bancoOrigen.getLlavePublica() != null) {
                headers.set("apikey", bancoOrigen.getLlavePublica());
            }

            HttpEntity<StatusReportDTO> request = new HttpEntity<>(statusReport, headers);

            restTemplate.postForEntity(webhookUrl, request, String.class);
            log.info("Webhook enviado exitosamente al banco origen {}", bicOrigen);

        } catch (Exception e) {
            log.error("Error notificando al banco origen {}: {}", bicOrigen, e.getMessage());
        }
    }

    private InstitucionDTO obtenerBancoDelDirectorio(String bic) {
        try {
            String url = directorioUrl + "/api/v1/instituciones/" + bic;
            return restTemplate.getForObject(url, InstitucionDTO.class);
        } catch (Exception e) {
            log.error("Error consultando banco {} en Directorio: {}", bic, e.getMessage());
            throw new BusinessException(IsoError.RC01.getCodigo() + " - Banco no encontrado en Directorio: " + bic);
        }
    }

    private void registrarMovimientoContable(String bic, UUID instructionId, BigDecimal monto, String tipo) {
        try {
            String url = contabilidadUrl + "/api/v1/ledger/movimientos";

            java.util.Map<String, Object> movimiento = new java.util.HashMap<>();
            movimiento.put("codigoBic", bic);
            movimiento.put("idInstruccion", instructionId.toString());
            movimiento.put("monto", monto);
            movimiento.put("tipo", tipo);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<java.util.Map<String, Object>> request = new HttpEntity<>(movimiento, headers);

            restTemplate.postForEntity(url, request, String.class);
            log.info("Movimiento {} registrado en Ledger: {} {} {}", tipo, bic, monto, tipo);
        } catch (Exception e) {
            log.error("Error registrando movimiento en Ledger: {}", e.getMessage());
            throw e;
        }
    }

    private void notificarCompensacion(String bic, BigDecimal monto, boolean esDebito) {
        try {
            String url = String.format("%s/api/v1/compensacion/acumular?bic=%s&monto=%s&esDebito=%s",
                    compensacionUrl, bic, monto.toString(), esDebito);

            restTemplate.postForEntity(url, null, Void.class);
            log.info("Compensación notificada para {} (esDebito={}): {}", bic, esDebito, monto);

        } catch (Exception e) {
            log.warn("Error notificando a Compensación (no crítico): {}", e.getMessage());
        }
    }
}
