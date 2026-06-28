package com.fyc.pendientes;

/**
 * Nodo generico — unidad basica de la cola encadenada.
 * Guarda el dato, su prioridad numerica y un puntero al siguiente.
 * menor prioridad = mas urgente (alta=1, media=2, baja=3).
 */
public class Nodo<T> {
    T dato;
    int prioridad;
    Nodo<T> siguiente;

    public Nodo(T dato, int prioridad) {
        this.dato = dato;
        this.prioridad = prioridad;
        this.siguiente = null;
    }
}
