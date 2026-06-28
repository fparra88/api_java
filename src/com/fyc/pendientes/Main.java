package com.fyc.pendientes;

import java.sql.SQLException;

/**
 * Punto de entrada. Carga config, conecta a MySQL, llena la cola de
 * prioridad y levanta el API HTTP.
 */
public class Main {
    public static void main(String[] args) {
        int puerto = Env.getInt("API_PORT", 19999);

        PendienteDAO dao = new PendienteDAO();
        GestorPendientes gestor = new GestorPendientes(dao);

        try {
            int n = gestor.recargarDesdeBD();
            System.out.println("Cargados " + n + " pendientes desde MySQL.");
            // Muestra el orden inicial en consola
            System.out.println("Orden de atencion:");
            int i = 1;
            for (Pendiente p : gestor.enOrden()) {
                System.out.println("  " + (i++) + ". " + p);
            }
        } catch (SQLException e) {
            System.err.println("No se pudo conectar/leer MySQL: " + e.getMessage());
            System.err.println("Revisa .env y que la tabla 'pendientes' exista (ver schema.sql).");
            // Sigue levantando el API; usa POST /api/recargar cuando la BD este lista.
        }

        try {
            new ApiServer(gestor, puerto).iniciar();
        } catch (Exception e) {
            System.err.println("No se pudo iniciar el API: " + e.getMessage());
            System.exit(1);
        }
    }
}
