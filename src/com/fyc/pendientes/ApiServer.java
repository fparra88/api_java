package com.fyc.pendientes;

import com.sun.net.httpserver.HttpExchange;
/* import com.sun.net.httpserver.HttpHandler; */
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * API HTTP usando el servidor embebido del JDK (sin frameworks).
 *
 * Endpoints (todos los GET/POST de cola aceptan ?usuario=xxx para trabajar
 * solo con las tareas de ese usuario; sin el parametro operan en modo global):
 *   GET  /api/pendientes              -> lista en orden de prioridad
 *   GET  /api/pendientes/siguiente    -> proximo a atender (peek)
 *   GET  /api/pendientes/en-proceso   -> array de tareas 'en proceso' (para finalizar)
 *   GET  /api/pendientes/{id}         -> busca por id (HashMap O(1))
 *   POST /api/pendientes/atender      -> atiende el siguiente (poll) -> estado 'en proceso'
 *   POST /api/pendientes/{id}/terminar -> marca como terminado
 *   POST /api/recargar                -> recarga la cola desde MySQL
 *   GET  /api/estadisticas            -> conteos por prioridad/estado
 */
public class ApiServer {

    private final GestorPendientes gestor;
    private final int puerto;

    public ApiServer(GestorPendientes gestor, int puerto) {
        this.gestor = gestor;
        this.puerto = puerto;
    }

    public void iniciar() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), 0);

        server.createContext("/api/pendientes", this::manejarPendientes);
        server.createContext("/api/recargar", this::manejarRecargar);
        server.createContext("/api/estadisticas", this::manejarEstadisticas);
        server.createContext("/", this::manejarRaiz);

        server.setExecutor(null);
        server.start();
        System.out.println("API escuchando en http://localhost:" + puerto);
        System.out.println("Prueba: http://localhost:" + puerto + "/api/pendientes");

        // Cierre limpio ante SIGTERM (systemctl stop) o Ctrl+C.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Apagando API...");
            server.stop(2);
        }));
    }

    // ---- handlers ----

    private void manejarPendientes(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        String path = ex.getRequestURI().getPath();
        String metodo = ex.getRequestMethod();
        String usuario = usuarioDeQuery(ex); // null = comportamiento global

        try {
            // POST /api/pendientes/nueva?usuario=&actividad=&prioridad=&observaciones=&fecha_promesa=&recurrencia=
            // FastAPI (registro-agregar) no conoce 'recurrencia'; las tareas
            // recurrentes se crean aqui para que la columna quede seteada.
            if (metodo.equalsIgnoreCase("POST") && path.endsWith("/nueva")) {
                String actividad = queryParam(ex, "actividad");
                String prioridad = queryParam(ex, "prioridad");
                if (usuario == null || actividad == null || actividad.isBlank()
                        || prioridad == null || prioridad.isBlank()) {
                    responder(ex, 400, "{\"error\":\"usuario, actividad y prioridad son obligatorios\"}");
                    return;
                }
                String observaciones = queryParam(ex, "observaciones");
                String recurrencia = queryParam(ex, "recurrencia");
                if (recurrencia != null && recurrencia.isBlank()) recurrencia = null;
                String fechaPromesaStr = queryParam(ex, "fecha_promesa");
                LocalDate fechaPromesa = null;
                if (fechaPromesaStr != null && !fechaPromesaStr.isBlank()) {
                    try {
                        fechaPromesa = LocalDate.parse(fechaPromesaStr);
                    } catch (DateTimeParseException dtpe) {
                        responder(ex, 400, "{\"error\":\"fecha_promesa invalida (usa yyyy-MM-dd)\"}");
                        return;
                    }
                }
                Pendiente creado = gestor.crear(usuario, actividad, prioridad, observaciones, fechaPromesa, recurrencia);
                responder(ex, 200, creado.toJson());
                return;
            }
            // POST /api/pendientes/{id}/recurrencia?usuario=&valor=diaria|semanal|quincenal|mensual (vacio = quitar)
            if (metodo.equalsIgnoreCase("POST") && path.endsWith("/recurrencia")) {
                String segmento = path.substring("/api/pendientes/".length()); // "5/recurrencia"
                String idStr = segmento.replace("/recurrencia", "");
                try {
                    String valor = queryParam(ex, "valor");
                    if (valor != null && valor.isBlank()) valor = null;
                    Pendiente actualizado = gestor.actualizarRecurrencia(Integer.parseInt(idStr), valor, usuario);
                    if (actualizado == null) { responder(ex, 404, "{\"mensaje\":\"No encontrado\"}"); return; }
                    responder(ex, 200, actualizado.toJson());
                } catch (NumberFormatException nfe) {
                    responder(ex, 400, "{\"error\":\"id invalido\"}");
                }
                return;
            }
            // POST /api/pendientes/{id}/terminar
            if (metodo.equalsIgnoreCase("POST") && path.endsWith("/terminar")) {
                String segmento = path.substring("/api/pendientes/".length()); // "5/terminar"
                String idStr = segmento.replace("/terminar", "");
                try {
                    boolean ok = gestor.terminar(Integer.parseInt(idStr), usuario);
                    if (!ok) { responder(ex, 404, "{\"mensaje\":\"No encontrado\"}"); return; }
                    responder(ex, 200, "{\"terminado\":true,\"id\":" + idStr + "}");
                } catch (NumberFormatException nfe) {
                    responder(ex, 400, "{\"error\":\"id invalido\"}");
                }
                return;
            }
            // POST /api/pendientes/atender?usuario=xxx        -> atiende el top de la cola
            // POST /api/pendientes/{id}/atender?usuario=xxx   -> atiende una tarea elegida
            if (metodo.equalsIgnoreCase("POST") && path.endsWith("/atender")) {
                String idStr = path.substring("/api/pendientes".length())
                                   .replace("/atender", "").replace("/", "");
                if (!idStr.isEmpty()) {
                    try {
                        Pendiente p = gestor.atenderPorId(Integer.parseInt(idStr), "en proceso", usuario);
                        if (p == null) { responder(ex, 404, "{\"mensaje\":\"No encontrado en cola\"}"); return; }
                        responder(ex, 200, "{\"atendido\":" + p.toJson() + ",\"enCola\":" + gestor.tamano(usuario) + "}");
                    } catch (NumberFormatException nfe) {
                        responder(ex, 400, "{\"error\":\"id invalido\"}");
                    }
                    return;
                }
                Pendiente p = gestor.atenderSiguiente("en proceso", usuario);
                if (p == null) { responder(ex, 404, "{\"mensaje\":\"No hay pendientes en cola\"}"); return; }
                responder(ex, 200, "{\"atendido\":" + p.toJson() + ",\"enCola\":" + gestor.tamano(usuario) + "}");
                return;
            }
            // GET /api/pendientes/en-proceso?usuario=xxx  -> tareas abiertas (array, puede ser varias)
            if (metodo.equalsIgnoreCase("GET") && path.endsWith("/en-proceso")) {
                responder(ex, 200, jsonLista(gestor.verEnProcesoLista(usuario)));
                return;
            }
            // GET /api/pendientes/siguiente?usuario=xxx
            if (metodo.equalsIgnoreCase("GET") && path.endsWith("/siguiente")) {
                Pendiente p = gestor.verSiguiente(usuario);
                if (p == null) { responder(ex, 404, "{\"mensaje\":\"Cola vacia\"}"); return; }
                responder(ex, 200, p.toJson());
                return;
            }
            // GET /api/pendientes/{id}
            String resto = path.substring("/api/pendientes".length());
            if (metodo.equalsIgnoreCase("GET") && resto.length() > 1) {
                String idStr = resto.replace("/", "");
                try {
                    Pendiente p = gestor.buscar(Integer.parseInt(idStr));
                    if (p == null) { responder(ex, 404, "{\"mensaje\":\"No encontrado\"}"); return; }
                    responder(ex, 200, p.toJson());
                } catch (NumberFormatException nfe) {
                    responder(ex, 400, "{\"error\":\"id invalido\"}");
                }
                return;
            }
            // GET /api/pendientes?usuario=xxx  -> lista ordenada (filtrada por usuario si viene el param)
            if (metodo.equalsIgnoreCase("GET")) {
                responder(ex, 200, jsonLista(gestor.enOrden(usuario)));
                return;
            }
            responder(ex, 405, "{\"error\":\"metodo no permitido\"}");
        } catch (SQLException e) {
            responder(ex, 500, "{\"error\":\"BD: " + escapar(e.getMessage()) + "\"}");
        }
    }

    private void manejarRecargar(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            responder(ex, 405, "{\"error\":\"usa POST\"}"); return;
        }
        try {
            int n = gestor.recargarDesdeBD();
            responder(ex, 200, "{\"recargados\":" + n + "}");
        } catch (SQLException e) {
            responder(ex, 500, "{\"error\":\"BD: " + escapar(e.getMessage()) + "\"}");
        }
    }

    private void manejarEstadisticas(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        responder(ex, 200, gestor.estadisticasJson(usuarioDeQuery(ex)));
    }

    private void manejarRaiz(HttpExchange ex) throws IOException {
        String body = "{\"servicio\":\"API Pendientes Zeutica\","
                + "\"nota\":\"agrega ?usuario=xxx para operar solo con las tareas de ese usuario\","
                + "\"endpoints\":["
                + "\"GET /api/pendientes\",\"GET /api/pendientes/siguiente\","
                + "\"GET /api/pendientes/en-proceso\","
                + "\"GET /api/pendientes/:id\",\"POST /api/pendientes/atender\","
                + "\"POST /api/pendientes/:id/terminar\","
                + "\"POST /api/recargar\",\"GET /api/estadisticas\"]}";
        responder(ex, 200, body);
    }

    // ---- util ----

    /** Serializa una lista de pendientes como array JSON. */
    private static String jsonLista(List<Pendiente> lista) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < lista.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(lista.get(i).toJson());
        }
        return sb.append(']').toString();
    }

    /** Usuario de la query (?usuario=Juan) normalizado: null si no viene o esta vacio. */
    private String usuarioDeQuery(HttpExchange ex) {
        String usuario = queryParam(ex, "usuario");
        if (usuario == null || usuario.isBlank()) return null;
        return usuario.trim();
    }

    /** Lee un parametro de la query string (?usuario=Juan&otro=x). Null si no viene. */
    private String queryParam(HttpExchange ex, String nombre) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String par : query.split("&")) {
            int eq = par.indexOf('=');
            String clave = (eq >= 0) ? par.substring(0, eq) : par;
            if (clave.equals(nombre)) {
                String valor = (eq >= 0) ? par.substring(eq + 1) : "";
                return java.net.URLDecoder.decode(valor, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void responder(HttpExchange ex, int codigo, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        // CORS — permite que el panel (navegador) consuma la API
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.sendResponseHeaders(codigo, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // devuelve true si era preflight y ya respondió
    private boolean preflight(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private static String escapar(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
