-- Creación de tabla maestra
CREATE TABLE IF NOT EXISTS transaccion (
    idInstruccion UUID PRIMARY KEY, -- InstructionId (ISO 20022)
    idMensaje VARCHAR(100),         -- MessageId técnico
    referenciaRed VARCHAR(64),      -- Folio del Switch
    bicEmisor VARCHAR(20),
    cuentaOrigen VARCHAR(34),
    bicReceptor VARCHAR(20),
    cuentaDestino VARCHAR(34),
    idBeneficiario VARCHAR(20),
    monto NUMERIC(18,2) NOT NULL,
    estado VARCHAR(20),             -- RECEIVED, QUEUED, COMPLETED, FAILED
    reintentos INTEGER DEFAULT 0,
    codigoError VARCHAR(10),
    idCicloCompensacion INTEGER,
    fechaCreacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fechaEncolado TIMESTAMP,
    fechaCompletado TIMESTAMP
);

-- Tabla de Idempotencia
CREATE TABLE IF NOT EXISTS respaldoIdempotencia (
    idInstruccion UUID PRIMARY KEY REFERENCES transaccion(idInstruccion),
    hashContenido VARCHAR(64),      -- MD5 del cuerpo crítico
    cuerpoRespuesta TEXT,           -- Replay JSON
    fechaExpiracion TIMESTAMP       -- TTL de 24h
);
