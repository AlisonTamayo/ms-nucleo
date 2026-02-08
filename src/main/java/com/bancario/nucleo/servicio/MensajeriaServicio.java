package com.bancario.nucleo.servicio;

import com.bancario.nucleo.config.RabbitConfig;
import com.bancario.nucleo.dto.iso.MensajeISO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MensajeriaServicio {

    private final RabbitTemplate rabbitTemplate;

    public void publicarTransferencia(MensajeISO iso) {
        try {
            // Regla de Oro: El Routing Key es el Banco Destino (targetBankId)
            String targetBankId = iso.getBody().getCreditor().getTargetBankId();

            if (targetBankId == null || targetBankId.isBlank()) {
                throw new IllegalArgumentException(
                        "El campo creditor.targetBankId es obligatorio para el enrutamiento.");
            }

            // Publicar al Direct Exchange
            log.info("RabbitMQ: Publicando mensaje {} hacia Banco {}", iso.getHeader().getMessageId(), targetBankId);

            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_NAME, targetBankId, iso);

            log.info("RabbitMQ: Publicación exitosa.");

        } catch (Exception e) {
            log.error("RabbitMQ Error: Fallo al publicar mensaje: {}", e.getMessage());
            throw new RuntimeException("Error de infraestructura de mensajería", e);
        }
    }

    public void publicarCompensacion(Object dto) {
        try {
            log.info("RabbitMQ: Enviando evento asíncrono a Compensación");
            // Usamos Routing Key directa a la cola interna
            rabbitTemplate.convertAndSend("q.switch.compensacion.in", dto);
        } catch (Exception e) {
            log.error("RabbitMQ Error: Fallo al publicar compensación: {}", e.getMessage());
            // No bloqueamos la Tx principal, pero logueamos error grave
        }
    }
}
