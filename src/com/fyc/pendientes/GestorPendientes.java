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
     * TODAS las tareas 'en proceso' del usuario (null = todas), en orden de atencion.
     * Un usuario puede tener varias abiertas siempre que sean del mismo nivel.
     */
    public synchronized List<Pendiente> verEnProcesoLista(String usuario) throws SQLException {
        List<Pendiente> lista = dao.listarPorEstado("en proceso", usuario);
        lista.sort(ORDEN);
        return lista;
    }

    /** Nivel de prioridad mas urgente entre las tareas 'en proceso' del usuario (0 = ninguna). */
    private int nivelEnProceso(String usuario) throws SQLException {
        int min = 0;
        for (Pendiente p : dao.listarPorEstado("en proceso", usuario)) {
            int n = p.prioridadNumerica();
            if (min == 0 || n < min) min = n;
        }
        return min;
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
            throws SQLException {
        return atenderSiguiente(nuevoEstado, null);
    }

    /**
     * Atiende el siguiente del usuario dado (null = global). Se puede tomar otra
     * tarea con una ya 'en proceso', mientras sea del mismo nivel de prioridad;
     * el estado abierto de un usuario nunca afecta a los demas.
     */
    public synchronized Pendiente atenderSiguiente(String nuevoEstado, String usuario)
            throws SQLException {
        Pendiente p;
        if (usuario == null) {
            p = cola.poll();
        } else {
            p = verSiguiente(usuario);
            if (p != null) cola.remove(p);
        }
        if (p == null) return null;

        // Las 'en proceso' salen de la cola: si alguna es de nivel superior al
        // siguiente en cola, no se puede bajar de nivel todavia. Se consulta la BD
        // (no solo memoria) para sobrevivir reinicios del server.
        int nivelAbierto = nivelEnProceso(usuario);
        if (nivelAbierto > 0 && nivelAbierto < p.prioridadNumerica()) {
            cola.offer(p); // devolver: no se atendio
            throw new IllegalStateException(
                "Tienes tareas de prioridad superior en proceso; terminalas antes de bajar de nivel");
        }

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
     * Atiende un pendiente especifico (elegido por el usuario) en vez del top de la cola.
     * Permite varias tareas 'en proceso' a la vez, siempre del MISMO nivel de prioridad:
     * el usuario puede abrir otra tarea del nivel actual sin cerrar la que ya tiene.
     * Reglas:
     *  - la tarea debe estar en cola y, si se da usuario, pertenecerle (null si no)
     *  - no debe quedar en cola otra tarea del usuario con prioridad MAS alta
     *    (IllegalStateException); dentro del mismo nivel cualquier tarea es valida.
     *  - tampoco puede tener abierta una tarea de prioridad MAS alta: las 'en proceso'
     *    salen de la cola, asi que se consultan aparte en la BD.
     */
    public synchronized Pendiente atenderPorId(int id, String nuevoEstado, String usuario)
            throws SQLException {
        Pendiente p = registro.get(id);
        if (p == null || !cola.contains(p)) return null;
        if (usuario != null && !esDeUsuario(p, usuario)) return null;

        for (Pendiente otro : cola) {
            if (usuario != null && !esDeUsuario(otro, usuario)) continue;
            if (otro.prioridadNumerica() < p.prioridadNumerica()) {
                throw new IllegalStateException(
                    "Hay tareas de prioridad superior pendientes (" + otro.prioridad + ")");
            }
        }
        int nivelAbierto = nivelEnProceso(usuario);
        if (nivelAbierto > 0 && nivelAbierto < p.prioridadNumerica()) {
            throw new IllegalStateException(
                "Tienes tareas de prioridad superior en proceso; terminalas antes de bajar de nivel");
        }

        cola.remove(p);
        dao.actualizarEstado(p.id, nuevoEstado);

        contadorEstado.merge(p.estado, -1, Integer::sum);
        p.estado = nuevoEstado;
        contadorEstado.merge(p.estado, 1, Integer::sum);
        atendidos++;
        if (p.usuario != null) {
            atendidosPorUsuario.merge(p.usuario.toLowerCase(), 1, Integer::sum);
        }
        return p;
    }

    /**
     * Marca un pendiente como terminado. Con usuario != null solo cierra tareas
     * de ese usuario (la BD valida pertenencia en el mismo UPDATE).
     * Devuelve false si el id no existe o no pertenece al usuario.
     */
    public synchronized boolean terminar(int id, String usuario) throws SQLException {
        // Actualiza la BD directo: una tarea 'en proceso' puede no estar en
        // 'registro' tras un reinicio (recargarDesdeBD solo carga 'pendiente').
        boolean filaActualizada = dao.actualizarEstado(id, "terminado", usuario);
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
