-- Tabla pendientes y datos de prueba
CREATE DATABASE IF NOT EXISTS fyc CHARACTER SET utf8mb4;
USE fyc;

CREATE TABLE IF NOT EXISTS pendientes (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    fecha         DATE         NOT NULL,
    usuario       VARCHAR(100) NOT NULL,
    actividad     VARCHAR(255) NOT NULL,
    prioridad     ENUM('baja','media','alta')                            NOT NULL DEFAULT 'media',
    estado        ENUM('pendiente','en proceso','terminado','en revision') NOT NULL DEFAULT 'pendiente',
    observaciones TEXT,
    fecha_promesa DATE
);

INSERT INTO pendientes (fecha, usuario, actividad, prioridad, estado, observaciones, fecha_promesa) VALUES
('2026-06-20','juan','Reparar tubería bodega','alta','pendiente','Fuga activa','2026-06-26'),
('2026-06-21','maria','Inventario tornillería','baja','pendiente','Conteo mensual','2026-07-01'),
('2026-06-22','pedro','Cotizar pintura cliente X','media','pendiente','Espera respuesta','2026-06-28'),
('2026-06-23','ana','Cambiar cerradura local 2','alta','en proceso','Ya comprada','2026-06-25'),
('2026-06-24','luis','Ordenar almacén','baja','pendiente',NULL,'2026-07-05'),
('2026-06-24','sofia','Atender garantía taladro','media','pendiente','Cliente molesto','2026-06-27');
