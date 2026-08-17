package com.fyc.pendientes;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceso a datos (JDBC) para la tabla 'pendientes'.
 * Credenciales leidas de .env via {@link Env}.
 */
public class PendienteDAO {

    private final String url;
    private final String usuario;
    private final String password;

    public PendienteDAO() {
        String host = Env.get("DB_HOST", "localhost");
        int port    = Env.getInt("DB_PORT", 3306);
        String db   = Env.get("DB_NAME", "fyc");
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        this.usuario  = Env.get("DB_USER", "root");
        this.password = Env.get("DB_PASSWORD", "");
    }

    private Connection conectar() throws SQLException {
        return DriverManager.getConnection(url, usuario, password);
    }

    /**
     * Lee todos los registros (o solo los no terminados si soloActivos=true).
     * Con soloActivos=true tambien excluye instancias recurrentes cuya
     * fecha_visible aun no llega (no deben aparecer en la cola todavia).
     */
    public List<Pendiente> listar(boolean soloActivos) throws SQLException {
        String sql = "SELECT id, fecha, usuario, actividad, prioridad, estado, observaciones, "
                   + "fecha_promesa, recurrencia, fecha_visible, plantilla_id FROM pendientes";
        if (soloActivos) sql += " WHERE estado = 'pendiente' AND (fecha_visible IS NULL OR fecha_visible <= CURDATE())";

        List<Pendiente> lista = new ArrayList<>();
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(mapear(rs));
            }
        }
        return lista;
    }

    /**
     * Todos los pendientes con el estado dado, ordenados por id.
     * Con usuario=null busca en todos (comportamiento global).
     * Necesario porque un usuario puede tener varias tareas 'en proceso' a la vez
     * (todas del mismo nivel de prioridad).
     */
    public List<Pendiente> listarPorEstado(String estado, String usuario) throws SQLException {
        String sql = "SELECT id, fecha, usuario, actividad, prioridad, estado, observaciones, "
                   + "fecha_promesa, recurrencia, fecha_visible, plantilla_id FROM pendientes "
                   + "WHERE estado = ?";
        if (usuario != null) sql += " AND LOWER(usuario) = LOWER(?)";
        sql += " ORDER BY id";

        List<Pendiente> lista = new ArrayList<>();
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, estado);
            if (usuario != null) ps.setString(2, usuario);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lista.add(mapear(rs));
            }
        }
        return lista;
    }

    /**
     * Busca un pendiente por id directo en BD (no en memoria). Necesario para
     * clonar una tarea recurrente al cerrarla: el registro en RAM puede no
     * tener la fila si el server se reinicio (recargarDesdeBD solo carga 'pendiente').
     */
    public Pendiente buscarPorId(int id) throws SQLException {
        String sql = "SELECT id, fecha, usuario, actividad, prioridad, estado, observaciones, "
                   + "fecha_promesa, recurrencia, fecha_visible, plantilla_id FROM pendientes WHERE id = ?";
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    /**
     * Inserta la siguiente instancia de una tarea recurrente (clon de 'base' con
     * nueva fecha). Devuelve el id generado. estado siempre 'pendiente'.
     */
    public int insertarInstanciaRecurrente(Pendiente base, LocalDate fechaPromesa, LocalDate fechaVisible, int plantillaId)
            throws SQLException {
        String sql = "INSERT INTO pendientes "
                   + "(fecha, usuario, actividad, prioridad, estado, observaciones, fecha_promesa, "
                   + "recurrencia, fecha_visible, plantilla_id) "
                   + "VALUES (CURDATE(), ?, ?, ?, 'pendiente', ?, ?, ?, ?, ?)";
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, base.usuario);
            ps.setString(2, base.actividad);
            ps.setString(3, base.prioridad);
            if (base.observaciones != null) ps.setString(4, base.observaciones); else ps.setNull(4, Types.VARCHAR);
            if (fechaPromesa != null) ps.setDate(5, Date.valueOf(fechaPromesa)); else ps.setNull(5, Types.DATE);
            ps.setString(6, base.recurrencia);
            if (fechaVisible != null) ps.setDate(7, Date.valueOf(fechaVisible)); else ps.setNull(7, Types.DATE);
            ps.setInt(8, plantillaId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        }
    }

    /**
     * Inserta un pendiente nuevo (creado desde el panel). estado siempre
     * 'pendiente', sin fecha_visible ni plantilla_id (visible de inmediato).
     * Devuelve el id generado.
     */
    public int insertarPendiente(String usuario, String actividad, String prioridad,
                                   String observaciones, LocalDate fechaPromesa, String recurrencia)
            throws SQLException {
        String sql = "INSERT INTO pendientes "
                   + "(fecha, usuario, actividad, prioridad, estado, observaciones, fecha_promesa, recurrencia) "
                   + "VALUES (CURDATE(), ?, ?, ?, 'pendiente', ?, ?, ?)";
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, usuario);
            ps.setString(2, actividad);
            ps.setString(3, prioridad);
            if (observaciones != null && !observaciones.isBlank()) ps.setString(4, observaciones); else ps.setNull(4, Types.VARCHAR);
            if (fechaPromesa != null) ps.setDate(5, Date.valueOf(fechaPromesa)); else ps.setNull(5, Types.DATE);
            if (recurrencia != null) ps.setString(6, recurrencia); else ps.setNull(6, Types.VARCHAR);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        }
    }

    /**
     * Cambia solo la columna 'recurrencia'. Con usuario != null solo si le
     * pertenece. Devuelve true si afecto alguna fila.
     */
    public boolean actualizarRecurrencia(int id, String recurrencia, String usuario) throws SQLException {
        String sql = "UPDATE pendientes SET recurrencia = ? WHERE id = ?";
        if (usuario != null) sql += " AND LOWER(usuario) = LOWER(?)";
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (recurrencia != null) ps.setString(1, recurrencia); else ps.setNull(1, Types.VARCHAR);
            ps.setInt(2, id);
            if (usuario != null) ps.setString(3, usuario);
            return ps.executeUpdate() > 0;
        }
    }

    /** Actualiza el estado de un pendiente. Devuelve true si afecto alguna fila. */
    public boolean actualizarEstado(int id, String nuevoEstado) throws SQLException {
        return actualizarEstado(id, nuevoEstado, null);
    }

    /**
     * Igual, pero solo si el pendiente pertenece al usuario (null = sin restriccion).
     * Evita que un usuario cierre tareas ajenas conociendo el id.
     */
    public boolean actualizarEstado(int id, String nuevoEstado, String usuario) throws SQLException {
        String sql = "UPDATE pendientes SET estado = ? WHERE id = ?";
        if (usuario != null) sql += " AND LOWER(usuario) = LOWER(?)";
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, nuevoEstado);
            ps.setInt(2, id);
            if (usuario != null) ps.setString(3, usuario);
            return ps.executeUpdate() > 0;
        }
    }

    private Pendiente mapear(ResultSet rs) throws SQLException {
        Pendiente p = new Pendiente();
        p.id            = rs.getInt("id");
        Date f          = rs.getDate("fecha");
        p.fecha         = (f != null) ? f.toLocalDate() : null;
        p.usuario       = rs.getString("usuario");
        p.actividad     = rs.getString("actividad");
        p.prioridad     = rs.getString("prioridad");
        p.estado        = rs.getString("estado");
        p.observaciones = rs.getString("observaciones");
        Date fp         = rs.getDate("fecha_promesa");
        p.fechaPromesa  = (fp != null) ? fp.toLocalDate() : null;
        p.recurrencia   = rs.getString("recurrencia");
        Date fv         = rs.getDate("fecha_visible");
        p.fechaVisible  = (fv != null) ? fv.toLocalDate() : null;
        int plantilla   = rs.getInt("plantilla_id");
        p.plantillaId   = rs.wasNull() ? null : plantilla;
        return p;
    }
}
