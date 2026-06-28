package com.fyc.pendientes;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    /** Lee todos los registros (o solo los no terminados si soloActivos=true). */
    public List<Pendiente> listar(boolean soloActivos) throws SQLException {
        String sql = "SELECT id, fecha, usuario, actividad, prioridad, estado, "
                   + "observaciones, fecha_promesa FROM pendientes";
        if (soloActivos) sql += " WHERE estado = 'pendiente'";

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
     * Devuelve el primer pendiente con el estado dado (o null si no hay).
     * Util para saber si quedo una tarea 'en proceso' sin terminar.
     */
    public Pendiente buscarPrimeroPorEstado(String estado) throws SQLException {
        String sql = "SELECT id, fecha, usuario, actividad, prioridad, estado, "
                   + "observaciones, fecha_promesa FROM pendientes "
                   + "WHERE estado = ? ORDER BY id LIMIT 1";
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, estado);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    /** Actualiza el estado de un pendiente. Devuelve true si afecto alguna fila. */
    public boolean actualizarEstado(int id, String nuevoEstado) throws SQLException {
        String sql = "UPDATE pendientes SET estado = ? WHERE id = ?";
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, nuevoEstado);
            ps.setInt(2, id);
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
        return p;
    }
}
