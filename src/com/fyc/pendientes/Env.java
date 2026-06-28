package com.fyc.pendientes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Cargador minimalista de archivo .env.
 * Lee pares CLAVE=VALOR, ignora comentarios (#) y lineas vacias.
 */
public final class Env {

    private static final Map<String, String> VALORES = new HashMap<>();

    static {
        cargar(Path.of(".env"));
    }

    private Env() {}

    private static void cargar(Path archivo) {
        if (!Files.exists(archivo)) {
            System.err.println("[Env] No se encontro .env — usando solo variables de entorno del sistema.");
            return;
        }
        try {
            for (String linea : Files.readAllLines(archivo)) {
                String l = linea.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                int eq = l.indexOf('=');
                if (eq < 0) continue;
                String clave = l.substring(0, eq).trim();
                String valor = l.substring(eq + 1).trim();
                VALORES.put(clave, valor);
            }
        } catch (IOException e) {
            System.err.println("[Env] Error leyendo .env: " + e.getMessage());
        }
    }

    /** Devuelve el valor de .env, o de variable de entorno del SO, o el default. */
    public static String get(String clave, String porDefecto) {
        String v = VALORES.get(clave);
        if (v == null || v.isEmpty()) v = System.getenv(clave);
        return (v == null || v.isEmpty()) ? porDefecto : v;
    }

    public static int getInt(String clave, int porDefecto) {
        try {
            return Integer.parseInt(get(clave, String.valueOf(porDefecto)));
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }
}
