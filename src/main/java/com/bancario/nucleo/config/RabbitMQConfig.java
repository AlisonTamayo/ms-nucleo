package com.bancario.nucleo.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange.transfers:ex.transfers.tx}")
    private String exchangeTransfers;

    @Value("${rabbitmq.exchange.dlx:ex.transfers.dlx}")
    private String exchangeDlx;

    @Value("${rabbitmq.queue.ttl:60000}")
    private int queueTtl;

    @Bean
    public DirectExchange transferExchange() {
        return new DirectExchange(exchangeTransfers, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(exchangeDlx, true, false);
    }

    @Bean
    public Queue nexusQueue() {
        return crearColaPrincipal(BancoDestino.NEXUS);
    }

    @Bean
    public Queue bantecQueue() {
        return crearColaPrincipal(BancoDestino.BANTEC);
    }

    @Bean
    public Queue arcbankQueue() {
        return crearColaPrincipal(BancoDestino.ARCBANK);
    }

    @Bean
    public Queue ecusolQueue() {
        return crearColaPrincipal(BancoDestino.ECUSOL);
    }

    private Queue crearColaPrincipal(BancoDestino banco) {
        return QueueBuilder.durable(banco.getQueueName())
                .withArgument("x-dead-letter-exchange", exchangeDlx)
                .withArgument("x-dead-letter-routing-key", banco.getRoutingKey())
                .withArgument("x-message-ttl", queueTtl)
                .build();
    }

    
    @Bean
    public Queue nexusDlq() {
        return QueueBuilder.durable(BancoDestino.NEXUS.getDlqName()).build();
    }

    @Bean
    public Queue bantecDlq() {
        return QueueBuilder.durable(BancoDestino.BANTEC.getDlqName()).build();
    }

    @Bean
    public Queue arcbankDlq() {
        return QueueBuilder.durable(BancoDestino.ARCBANK.getDlqName()).build();
    }

    @Bean
    public Queue ecusolDlq() {
        return QueueBuilder.durable(BancoDestino.ECUSOL.getDlqName()).build();
    }

    @Bean
    public Binding nexusBinding() {
        return BindingBuilder.bind(nexusQueue()).to(transferExchange())
                .with(BancoDestino.NEXUS.getRoutingKey());
    }

    @Bean
    public Binding bantecBinding() {
        return BindingBuilder.bind(bantecQueue()).to(transferExchange())
                .with(BancoDestino.BANTEC.getRoutingKey());
    }

    @Bean
    public Binding arcbankBinding() {
        return BindingBuilder.bind(arcbankQueue()).to(transferExchange())
                .with(BancoDestino.ARCBANK.getRoutingKey());
    }

    @Bean
    public Binding ecusolBinding() {
        return BindingBuilder.bind(ecusolQueue()).to(transferExchange())
                .with(BancoDestino.ECUSOL.getRoutingKey());
    }

    @Bean
    public Binding nexusDlqBinding() {
        return BindingBuilder.bind(nexusDlq()).to(deadLetterExchange())
                .with(BancoDestino.NEXUS.getRoutingKey());
    }

    @Bean
    public Binding bantecDlqBinding() {
        return BindingBuilder.bind(bantecDlq()).to(deadLetterExchange())
                .with(BancoDestino.BANTEC.getRoutingKey());
    }

    @Bean
    public Binding arcbankDlqBinding() {
        return BindingBuilder.bind(arcbankDlq()).to(deadLetterExchange())
                .with(BancoDestino.ARCBANK.getRoutingKey());
    }

    @Bean
    public Binding ecusolDlqBinding() {
        return BindingBuilder.bind(ecusolDlq()).to(deadLetterExchange())
                .with(BancoDestino.ECUSOL.getRoutingKey());
    }
    
    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jackson2JsonMessageConverter());
        return rabbitTemplate;
    }
}
