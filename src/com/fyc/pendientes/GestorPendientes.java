package com.fyc.pendientes;

import java.sql.SQLException;
import java.time.LocalDate;
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
     * tarea con una ya 'en proceso': el usuario elige libremente, sin importar
     * la prioridad de lo que ya tenga abierto. El estado de un usuario nunca
     * afecta a los demas.
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
     * La prioridad no restringe la eleccion: el usuario puede tomar cualquier tarea
     * suya en cola, tenga o no otras abiertas, sin importar el nivel de ninguna.
     * Reglas:
     *  - la tarea debe estar en cola y, si se da usuario, pertenecerle (null si no)
     */
    public synchronized Pendiente atenderPorId(int id, String nuevoEstado, String usuario)
            throws SQLException {
        Pendiente p = registro.get(id);
        if (p == null || !cola.contains(p)) return null;
        if (usuario != null && !esDeUsuario(p, usuario)) return null;

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
     * Si la tarea tiene 'recurrencia', clona una instancia nueva con la
     * siguiente fecha (oculta hasta llegar via fecha_visible) — la tarea nunca
     * desaparece del todo, solo espera su turno.
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

        // La fila en memoria puede no traer recurrencia/plantillaId si nunca se
        // recargo desde que se agrego la columna; se relee de BD para estar seguros.
        Pendiente completa = dao.buscarPorId(id);
        if (completa != null && completa.recurrencia != null) {
            LocalDate ancla = completa.fechaPromesa != null ? completa.fechaPromesa
                             : (completa.fecha != null ? completa.fecha : LocalDate.now());
            LocalDate proxima = proximaFecha(ancla, completa.recurrencia);
            int plantillaId = completa.plantillaId != null ? completa.plantillaId : completa.id;
            dao.insertarInstanciaRecurrente(completa, proxima, proxima, plantillaId);
        }
        return true;
    }

    /**
     * Crea un pendiente nuevo directo en BD y lo suma a la cola en memoria
     * (visible de inmediato: sin fecha_visible, sin plantilla_id). FastAPI no
     * conoce el campo 'recurrencia', asi que la creacion de tareas recurrentes
     * pasa por aqui en vez de por el endpoint de FastAPI.
     */
    public synchronized Pendiente crear(String usuario, String actividad, String prioridad,
                                          String observaciones, LocalDate fechaPromesa, String recurrencia)
            throws SQLException {
        int id = dao.insertarPendiente(usuario, actividad, prioridad, observaciones, fechaPromesa, recurrencia);
        Pendiente p = new Pendiente();
        p.id = id;
        p.fecha = LocalDate.now();
        p.usuario = usuario;
        p.actividad = actividad;
        p.prioridad = prioridad;
        p.estado = "pendiente";
        p.observaciones = observaciones;
        p.fechaPromesa = fechaPromesa;
        p.recurrencia = recurrencia;
        p.fechaVisible = null;
        p.plantillaId = null;

        cola.offer(p);
        registro.put(p.id, p);
        contadorPrioridad.merge(p.prioridad, 1, Integer::sum);
        contadorEstado.merge(p.estado, 1, Integer::sum);
        return p;
    }

    /**
     * Cambia solo la 'recurrencia' de un pendiente existente (usado al editar
     * desde el panel, ya que FastAPI tampoco conoce este campo). null = quitarla.
     * Devuelve el pendiente actualizado, o null si no existe / no pertenece al usuario.
     */
    public synchronized Pendiente actualizarRecurrencia(int id, String recurrencia, String usuario)
            throws SQLException {
        boolean ok = dao.actualizarRecurrencia(id, recurrencia, usuario);
        if (!ok) return null;

        Pendiente p = registro.get(id);
        if (p != null) p.recurrencia = recurrencia; // misma referencia que en 'cola'
        return dao.buscarPorId(id);
    }

    /** diaria +1d, semanal +7d, quincenal +15d, mensual +1 mes. */
    private static LocalDate proximaFecha(LocalDate ancla, String recurrencia) {
        return switch (recurrencia.toLowerCase()) {
            case "diaria"    -> ancla.plusDays(1);
            case "semanal"   -> ancla.plusWeeks(1);
            case "quincenal" -> ancla.plusDays(15);
            case "mensual"   -> ancla.plusMonths(1);
            default           -> ancla;
        };
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
