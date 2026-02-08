package com.bancario.nucleo.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Filtro de seguridad para validar que las peticiones vienen del APIM.
 * El APIM inyecta el header x-origin-secret en todas las peticiones.
 * 
 * Si la petición NO tiene este header, se rechaza (403 Forbidden).
 * Esto previene acceso directo al backend sin pasar por el APIM.
 */
@Slf4j
@Component
@Order(1)
public class ApimSecurityFilter implements Filter {

    @Value("${apim.origin.secret:}")
    private String expectedSecret;

    @Value("${apim.security.enabled:false}")
    private boolean securityEnabled;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Si la seguridad está deshabilitada (env local), permitir todo
        if (!securityEnabled) {
            chain.doFilter(request, response);
            return;
        }

        // Permitir requests de health check sin validación
        String path = httpRequest.getRequestURI();
        if (path.contains("/actuator/health") || path.contains("/health")) {
            chain.doFilter(request, response);
            return;
        }

        // Validar header x-origin-secret
        String receivedSecret = httpRequest.getHeader("x-origin-secret");

        if (receivedSecret == null || !receivedSecret.equals(expectedSecret)) {
            log.warn("Petición rechazada - Header x-origin-secret inválido o ausente. URI: {}", path);
            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Invalid origin\"}");
            return;
        }

        // Petición válida, continuar
        chain.doFilter(request, response);
    }
}
