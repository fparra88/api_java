package com.fyc.pendientes;

import com.sun.net.httpserver.HttpExchange;
/* import com.sun.net.httpserver.HttpHandler; */
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

/**
 * API HTTP usando el servidor embebido del JDK (sin frameworks).
 *
 * Endpoints (todos los GET/POST de cola aceptan ?usuario=xxx para trabajar
 * solo con las tareas de ese usuario; sin el parametro operan en modo global):
 *   GET  /api/pendientes              -> lista en orden de prioridad
 *   GET  /api/pendientes/siguiente    -> proximo a atender (peek)
 *   GET  /api/pendientes/en-proceso   -> tarea 'en proceso' bloqueante (para finalizar)
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
            // POST /api/pendientes/{id}/terminar
            if (metodo.equalsIgnoreCase("POST") && path.endsWith("/terminar")) {
                String segmento = path.substring("/api/pendientes/".length()); // "5/terminar"
                String idStr = segmento.replace("/terminar", "");
                try {
                    boolean ok = gestor.terminar(Integer.parseInt(idStr));
                    if (!ok) { responder(ex, 404, "{\"mensaje\":\"No encontrado\"}"); return; }
                    responder(ex, 200, "{\"terminado\":true,\"id\":" + idStr + "}");
                } catch (NumberFormatException nfe) {
                    responder(ex, 400, "{\"error\":\"id invalido\"}");
                }
                return;
            }
            // POST /api/pendientes/atender?usuario=xxx
            if (metodo.equalsIgnoreCase("POST") && path.endsWith("/atender")) {
                Pendiente p = gestor.atenderSiguiente("en proceso", usuario);
                if (p == null) { responder(ex, 404, "{\"mensaje\":\"No hay pendientes en cola\"}"); return; }
                responder(ex, 200, "{\"atendido\":" + p.toJson() + ",\"enCola\":" + gestor.tamano(usuario) + "}");
                return;
            }
            // GET /api/pendientes/en-proceso?usuario=xxx  -> tarea bloqueante a finalizar
            if (metodo.equalsIgnoreCase("GET") && path.endsWith("/en-proceso")) {
                Pendiente p = gestor.verEnProceso(usuario);
                if (p == null) { responder(ex, 404, "{\"mensaje\":\"No hay tarea en proceso\"}"); return; }
                responder(ex, 200, p.toJson());
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
                List<Pendiente> lista = gestor.enOrden(usuario);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < lista.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(lista.get(i).toJson());
                }
                sb.append(']');
                responder(ex, 200, sb.toString());
                return;
            }
            responder(ex, 405, "{\"error\":\"metodo no permitido\"}");
        } catch (HayTareaEnProcesoException e) {
            // 409 Conflict: hay una tarea sin terminar; el front debe cerrarla primero.
            responder(ex, 409, "{\"error\":\"" + escapar(e.getMessage())
                    + "\",\"enProceso\":" + e.tarea.toJson() + "}");
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
