package com.fyc.pendientes;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Nucleo del sistema. Combina:
 *  - PriorityQueue nativa de Java (atencion por prioridad, O(log n)).
 *  - HashMap registro id->Pendiente (busqueda O(1)).
 *  - HashMap contadores por prioridad y por estado (estadisticas O(1)).
 * Desempate: prioridad -> fecha_promesa mas cercana -> fecha mas antigua.
 */
public class GestorPendientes {

    /** Orden de atencion: menor prioridadNumerica primero, luego promesa, luego fecha. */
    public static final Comparator<Pendiente> ORDEN =
        Comparator.comparingInt(Pendiente::prioridadNumerica)
                  .thenComparing(p -> p.fechaPromesa, Comparator.nullsLast(Comparator.naturalOrder()))
                  .thenComparing(p -> p.fecha,        Comparator.nullsLast(Comparator.naturalOrder()));

    private final PendienteDAO dao;
    private PriorityQueue<Pendiente> cola;
    private final Map<Integer, Pendiente> registro;        // id -> pendiente (O(1))
    private final Map<String, Integer> contadorPrioridad;  // alta/media/baja -> cantidad
    private final Map<String, Integer> contadorEstado;     // estado -> cantidad
    private final Map<String, Integer> atendidosPorUsuario; // usuario (lower) -> atendidos
    private int atendidos;

    public GestorPendientes(PendienteDAO dao) {
        this.dao = dao;
        this.cola = new PriorityQueue<>(ORDEN);
        this.registro = new HashMap<>();
        this.contadorPrioridad = new HashMap<>();
        this.contadorEstado = new HashMap<>();
        this.atendidosPorUsuario = new HashMap<>();
        this.atendidos = 0;
    }

    /** true si el pendiente pertenece al usuario (ignora mayusculas/minusculas). */
    private static boolean esDeUsuario(Pendiente p, String usuario) {
        return p.usuario != null && p.usuario.equalsIgnoreCase(usuario);
    }

    /** Recarga la cola desde la base de datos (solo pendientes no terminados). */
    public synchronized int recargarDesdeBD() throws SQLException {
        cola.clear();
        registro.clear();
        contadorPrioridad.clear();
        contadorEstado.clear();

        List<Pendiente> filas = dao.listar(true);
        for (Pendiente p : filas) {
            cola.offer(p);
            registro.put(p.id, p);
            contadorPrioridad.merge(p.prioridad, 1, Integer::sum);
            contadorEstado.merge(p.estado, 1, Integer::sum);
        }
        return filas.size();
    }

    /** Lista de pendientes en ORDEN de atencion (sin destruir la cola). */
    public synchronized List<Pendiente> enOrden() {
        return enOrden(null);
    }

    /** Lista en orden de atencion, filtrada por usuario (null = todos). */
    public synchronized List<Pendiente> enOrden(String usuario) {
        List<Pendiente> copia = new ArrayList<>();
        for (Pendiente p : cola) {
            if (usuario == null || esDeUsuario(p, usuario)) copia.add(p);
        }
        copia.sort(ORDEN);
        return copia;
    }

    /** Mira el proximo a atender sin sacarlo. null si vacia. */
    public synchronized Pendiente verSiguiente() {
        return cola.peek();
    }

    /** Proximo a atender del usuario dado (null = global). */
    public synchronized Pendiente verSiguiente(String usuario) {
        if (usuario == null) return cola.peek();
        List<Pendiente> orden = enOrden(usuario);
        return orden.isEmpty() ? null : orden.get(0);
    }

    /**
     * Devuelve la tarea actualmente 'en proceso' (la que bloquea atenderSiguiente),
     * o null si no hay ninguna. Consulta la BD: las 'en proceso' no viven en la cola
     * (recargarDesdeBD solo carga 'pendiente'). El front la usa para finalizarla.
     */
    public synchronized Pendiente verEnProceso() throws SQLException {
        return dao.buscarPrimeroPorEstado("en proceso");
    }

    /** Tarea 'en proceso' del usuario dado (null = cualquiera). */
    public synchronized Pendiente verEnProceso(String usuario) throws SQLException {
        return dao.buscarPrimeroPorEstado("en proceso", usuario);
    }

    /** Busca un pendiente por id en O(1). */
    public synchronized Pendiente buscar(int id) {
        return registro.get(id);
    }

    /**
     * Atiende el siguiente: lo saca de la cola y marca su estado en la BD.
     * Devuelve el pendiente atendido, o null si no hay nada en cola.
     */
    public synchronized Pendiente atenderSiguiente(String nuevoEstado)
            throws SQLException, HayTareaEnProcesoException {
        return atenderSiguiente(nuevoEstado, null);
    }

    /**
     * Atiende el siguiente del usuario dado (null = global). El bloqueo por tarea
     * 'en proceso' tambien es por usuario: la tarea abierta de un usuario no
     * bloquea a los demas.
     */
    public synchronized Pendiente atenderSiguiente(String nuevoEstado, String usuario)
            throws SQLException, HayTareaEnProcesoException {
        // Guard: no entregar la proxima si quedo una tarea 'en proceso' sin terminar.
        // Se consulta la BD (no solo memoria) para sobrevivir reinicios del server.
        Pendiente bloqueante = dao.buscarPrimeroPorEstado("en proceso", usuario);
        if (bloqueante != null) throw new HayTareaEnProcesoException(bloqueante);

        Pendiente p;
        if (usuario == null) {
            p = cola.poll();
        } else {
            p = verSiguiente(usuario);
            if (p != null) cola.remove(p);
        }
        if (p == null) return null;

        dao.actualizarEstado(p.id, nuevoEstado);

        contadorEstado.merge(p.estado, -1, Integer::sum); // estado viejo baja
        p.estado = nuevoEstado;
        contadorEstado.merge(p.estado, 1, Integer::sum);  // estado nuevo sube
        atendidos++;
        if (p.usuario != null) {
            atendidosPorUsuario.merge(p.usuario.toLowerCase(), 1, Integer::sum);
        }
        // se queda en 'registro' para consultas historicas
        return p;
    }

    /**
     * Marca un pendiente como terminado (debe estar en registro, tipicamente 'en proceso').
     * Devuelve false si el id no existe en registro.
     */
    public synchronized boolean terminar(int id) throws SQLException {
        // Actualiza la BD directo: una tarea 'en proceso' puede no estar en
        // 'registro' tras un reinicio (recargarDesdeBD solo carga 'pendiente').
        boolean filaActualizada = dao.actualizarEstado(id, "terminado");
        if (!filaActualizada) return false;

        Pendiente p = registro.get(id);
        if (p != null) {
            contadorEstado.merge(p.estado, -1, Integer::sum);
            p.estado = "terminado";
            contadorEstado.merge("terminado", 1, Integer::sum);
        }
        return true;
    }

    public synchronized int tamano()    { return cola.size(); }
    public synchronized int atendidos() { return atendidos; }
    public synchronized boolean vacia() { return cola.isEmpty(); }

    /** Cantidad en cola del usuario dado (null = total). */
    public synchronized int tamano(String usuario) {
        if (usuario == null) return cola.size();
        int n = 0;
        for (Pendiente p : cola) {
            if (esDeUsuario(p, usuario)) n++;
        }
        return n;
    }

    /** Estadisticas en JSON: totales, atendidos, conteos por prioridad y estado. */
    public synchronized String estadisticasJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{')
          .append("\"enCola\":").append(cola.size()).append(',')
          .append("\"atendidos\":").append(atendidos).append(',')
          .append("\"porPrioridad\":").append(mapaJson(contadorPrioridad)).append(',')
          .append("\"porEstado\":").append(mapaJson(contadorEstado))
          .append('}');
        return sb.toString();
    }

    /** Estadisticas limitadas a un usuario (null = globales). */
    public synchronized String estadisticasJson(String usuario) {
        if (usuario == null) return estadisticasJson();

        Map<String, Integer> porPrioridad = new HashMap<>();
        Map<String, Integer> porEstado = new HashMap<>();
        int enCola = 0;
        for (Pendiente p : cola) {
            if (!esDeUsuario(p, usuario)) continue;
            enCola++;
            porPrioridad.merge(p.prioridad, 1, Integer::sum);
            porEstado.merge(p.estado, 1, Integer::sum);
        }
        int atendidosUsuario = atendidosPorUsuario.getOrDefault(usuario.toLowerCase(), 0);

        StringBuilder sb = new StringBuilder();
        sb.append('{')
          .append("\"usuario\":\"").append(usuario.replace("\"", "\\\"")).append("\",")
          .append("\"enCola\":").append(enCola).append(',')
          .append("\"atendidos\":").append(atendidosUsuario).append(',')
          .append("\"porPrioridad\":").append(mapaJson(porPrioridad)).append(',')
          .append("\"porEstado\":").append(mapaJson(porEstado))
          .append('}');
        return sb.toString();
    }

    private static String mapaJson(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean primero = true;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue() == null || e.getValue() == 0) continue;
            if (!primero) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
            primero = false;
        }
        return sb.append('}').toString();
    }
}
