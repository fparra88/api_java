package com.fyc.pendientes;

import java.util.ArrayList;
import java.util.List;

/**
 * Cola con prioridad IMPLEMENTADA A MANO con nodos encadenados.
 * Mantiene el orden en cada insercion: la cabeza siempre es el mas urgente.
 * (Version didactica; el GestorPendientes usa ademas PriorityQueue nativa.)
 */
public class ColaPrioridad<T> {
    private Nodo<T> cabeza;
    private int tamano;

    public ColaPrioridad() {
        this.cabeza = null;
        this.tamano = 0;
    }

    /** Inserta manteniendo orden por prioridad (menor numero = al frente). */
    public void encolar(T dato, int prioridad) {
        Nodo<T> nuevo = new Nodo<>(dato, prioridad);
        if (cabeza == null || prioridad < cabeza.prioridad) {
            nuevo.siguiente = cabeza;
            cabeza = nuevo;
        } else {
            Nodo<T> actual = cabeza;
            while (actual.siguiente != null && actual.siguiente.prioridad <= prioridad) {
                actual = actual.siguiente;
            }
            nuevo.siguiente = actual.siguiente;
            actual.siguiente = nuevo;
        }
        tamano++;
    }

    /** Saca y devuelve el de mayor prioridad (la cabeza). */
    public T desencolar() {
        if (cabeza == null) throw new RuntimeException("Cola vacia");
        T dato = cabeza.dato;
        cabeza = cabeza.siguiente;
        tamano--;
        return dato;
    }

    /** Mira al siguiente sin quitarlo. */
    public T verSiguiente() {
        if (cabeza == null) throw new RuntimeException("Cola vacia");
        return cabeza.dato;
    }

    /** Devuelve todos en orden de atencion, sin destruir la cola. */
    public List<T> aLista() {
        List<T> lista = new ArrayList<>(tamano);
        Nodo<T> actual = cabeza;
        while (actual != null) {
            lista.add(actual.dato);
            actual = actual.siguiente;
        }
        return lista;
    }

    public boolean estaVacia() { return cabeza == null; }
    public int getTamano()     { return tamano; }
}
