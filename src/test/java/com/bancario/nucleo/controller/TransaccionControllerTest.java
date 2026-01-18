package com.bancario.nucleo.controller;

import com.bancario.nucleo.dto.ReturnRequestDTO;
import com.bancario.nucleo.service.TransaccionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransaccionController.class)
public class TransaccionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransaccionService transaccionService;

    @Test
    public void testProcesarDevolucion() throws Exception {
        ReturnRequestDTO dto = new ReturnRequestDTO();
        ReturnRequestDTO.Header header = new ReturnRequestDTO.Header();
        header.setMessageId("MSG-001");
        dto.setHeader(header);

        ReturnRequestDTO.Body body = new ReturnRequestDTO.Body();
        body.setOriginalInstructionId(UUID.randomUUID().toString());
        dto.setBody(body);

        when(transaccionService.procesarDevolucion(any(ReturnRequestDTO.class))).thenReturn(new Object());

        mockMvc.perform(post("/api/v1/transacciones/devoluciones")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());
    }
}
