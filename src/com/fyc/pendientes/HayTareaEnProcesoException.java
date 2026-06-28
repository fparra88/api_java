package com.fyc.pendientes;

/**
 * Se lanza al intentar atender el siguiente en cola cuando ya existe una tarea
 * en estado 'en proceso' sin terminar. Obliga a cerrar (terminar) la tarea
 * actual antes de entregar la proxima.
 */
public class HayTareaEnProcesoException extends Exception {

    /** La tarea que sigue en proceso y bloquea la entrega de la siguiente. */
    public final Pendiente tarea;

    public HayTareaEnProcesoException(Pendiente tarea) {
        super("Hay una tarea en proceso (#" + (tarea != null ? tarea.id : "?")
                + "). Termina esa tarea antes de atender la siguiente.");
        this.tarea = tarea;
    }
}
