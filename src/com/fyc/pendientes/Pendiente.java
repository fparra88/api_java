package com.fyc.pendientes;

import java.time.LocalDate;

/**
 * Modelo de una fila de la tabla 'pendientes'.
 * Convierte la prioridad textual (baja/media/alta) a numero para la cola.
 */
public class Pendiente {
    public int id;
    public LocalDate fecha;
    public String usuario;
    public String actividad;
    public String prioridad;     // baja | media | alta
    public String estado;        // pendiente | en proceso | terminado | en revision
    public String observaciones;
    public LocalDate fechaPromesa;

    /** alta=1, media=2, baja=3 (menor numero = mas urgente). */
    public int prioridadNumerica() {
        if (prioridad == null) return 3;
        return switch (prioridad.toLowerCase()) {
            case "alta"  -> 1;
            case "media" -> 2;
            case "baja"  -> 3;
            default       -> 3;
        };
    }

    /** Serializa a JSON manual (sin dependencias externas). */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{')
          .append("\"id\":").append(id).append(',')
          .append("\"fecha\":").append(jsonStr(fecha)).append(',')
          .append("\"usuario\":").append(jsonStr(usuario)).append(',')
          .append("\"actividad\":").append(jsonStr(actividad)).append(',')
          .append("\"prioridad\":").append(jsonStr(prioridad)).append(',')
          .append("\"prioridadNumerica\":").append(prioridadNumerica()).append(',')
          .append("\"estado\":").append(jsonStr(estado)).append(',')
          .append("\"observaciones\":").append(jsonStr(observaciones)).append(',')
          .append("\"fechaPromesa\":").append(jsonStr(fechaPromesa))
          .append('}');
        return sb.toString();
    }

    private static String jsonStr(Object o) {
        if (o == null) return "null";
        String s = o.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "\"" + s + "\"";
    }

    @Override
    public String toString() {
        return "[" + prioridad + "] #" + id + " " + usuario + " — " + actividad
                + " (" + estado + ")";
    }
}
