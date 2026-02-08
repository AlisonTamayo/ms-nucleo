package com.bancario.nucleo.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum BancoDestino {
    NEXUS("NEXUS", "q.bank.NEXUS.in"),
    BANTEC("BANTEC", "q.bank.BANTEC.in"),
    ARCBANK("ARCBANK", "q.bank.ARCBANK.in"),
    ECUSOL("ECUSOL", "q.bank.ECUSOL.in");

    private final String routingKey;
    private final String queueName;

    BancoDestino(String routingKey, String queueName) {
        this.routingKey = routingKey;
        this.queueName = queueName;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getQueueName() {
        return queueName;
    }

    public String getDlqName() {
        return queueName.replace(".in", ".dlq");
    }

    public static Set<String> getAllRoutingKeys() {
        return Arrays.stream(values())
                .map(BancoDestino::getRoutingKey)
                .collect(Collectors.toSet());
    }

    public static BancoDestino fromRoutingKey(String routingKey) {
        return Arrays.stream(values())
                .filter(b -> b.routingKey.equalsIgnoreCase(routingKey))
                .findFirst()
                .orElse(null);
    }

    public static boolean isValid(String routingKey) {
        return fromRoutingKey(routingKey) != null;
    }
}
