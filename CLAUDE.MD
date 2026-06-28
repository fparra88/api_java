# 🎯 Experto en Backend Java: Sistema de Turnos y Atención con Prioridades

> Guía completa sobre colas, nodos, constructores y HashMap aplicados a sistemas de turnos reales.

---

## 📌 Conceptos Fundamentales

### ¿Qué es un sistema de turnos con prioridades?

Un sistema de atención que **no sirve en orden de llegada**, sino según el nivel de urgencia o importancia de cada solicitud. Ejemplo real: un hospital donde los pacientes críticos se atienden antes que los de consulta general.

---

## 🧱 Nodo — La unidad básica

Todo sistema de colas encadenadas parte de un **Nodo**. Es el bloque que guarda el dato y apunta al siguiente.

```java
public class Nodo<T> {
    T dato;           // El contenido (puede ser cualquier tipo)
    int prioridad;    // Número: menor = más urgente (o al revés, según diseño)
    Nodo<T> siguiente; // Puntero al siguiente nodo en la cadena

    // Constructor principal
    public Nodo(T dato, int prioridad) {
        this.dato = dato;
        this.prioridad = prioridad;
        this.siguiente = null;  // Por defecto no apunta a nadie
    }
}
```

**Regla clave:** El nodo no sabe nada de la cola, solo conoce a su vecino inmediato.

---

## 🏗️ Constructores — Cómo inicializar correctamente

### Constructor vacío vs constructor con parámetros

```java
public class ColaPrioridad<T> {
    private Nodo<T> cabeza;  // El primero en ser atendido
    private int tamaño;

    // Constructor vacío — cola lista para recibir elementos
    public ColaPrioridad() {
        this.cabeza = null;
        this.tamaño = 0;
    }

    // Constructor con elemento inicial — útil para no tener cola vacía
    public ColaPrioridad(T datoInicial, int prioridad) {
        this.cabeza = new Nodo<>(datoInicial, prioridad);
        this.tamaño = 1;
    }
}
```

**¿Cuándo usar cada uno?**
- Constructor vacío → cuando no sabes qué llegará primero (caso más común).
- Constructor con elemento → cuando ya tienes un dato inicial garantizado.

---

## 🔗 Cola con Prioridad — Implementación Manual (sin PriorityQueue de Java)

```java
public class ColaPrioridad<T> {
    private Nodo<T> cabeza;
    private int tamaño;

    public ColaPrioridad() {
        this.cabeza = null;
        this.tamaño = 0;
    }

    /**
     * Inserta manteniendo el orden por prioridad.
     * Prioridad 1 = más urgente, se va al frente.
     */
    public void encolar(T dato, int prioridad) {
        Nodo<T> nuevo = new Nodo<>(dato, prioridad);

        // Caso 1: cola vacía o nuevo es más urgente que el primero
        if (cabeza == null || prioridad < cabeza.prioridad) {
            nuevo.siguiente = cabeza;
            cabeza = nuevo;
        } else {
            // Caso 2: encontrar la posición correcta
            Nodo<T> actual = cabeza;
            while (actual.siguiente != null && actual.siguiente.prioridad <= prioridad) {
                actual = actual.siguiente;
            }
            nuevo.siguiente = actual.siguiente;
            actual.siguiente = nuevo;
        }
        tamaño++;
    }

    /**
     * Saca y devuelve el elemento con mayor prioridad (el de la cabeza).
     */
    public T desencolar() {
        if (cabeza == null) throw new RuntimeException("Cola vacía");
        T dato = cabeza.dato;
        cabeza = cabeza.siguiente;
        tamaño--;
        return dato;
    }

    /**
     * Ver quién es el próximo sin quitarlo.
     */
    public T verSiguiente() {
        if (cabeza == null) throw new RuntimeException("Cola vacía");
        return cabeza.dato;
    }

    public boolean estaVacia() { return cabeza == null; }
    public int getTamaño()     { return tamaño; }
}
```

---

## ☕ PriorityQueue de Java — La versión nativa

Java tiene su propia implementación lista para usar. Importante saberla bien.

```java
import java.util.PriorityQueue;
import java.util.Comparator;

// Solicitud de turno
public class Solicitud {
    String nombre;
    int prioridad;   // 1 = crítico, 2 = urgente, 3 = normal
    String tipo;

    public Solicitud(String nombre, int prioridad, String tipo) {
        this.nombre = nombre;
        this.prioridad = prioridad;
        this.tipo = tipo;
    }

    @Override
    public String toString() {
        return "[P" + prioridad + "] " + nombre + " → " + tipo;
    }
}

// Uso con Comparator personalizado
PriorityQueue<Solicitud> colaTurnos = new PriorityQueue<>(
    Comparator.comparingInt(s -> s.prioridad)  // menor número = primero
);

colaTurnos.offer(new Solicitud("Juan", 3, "Consulta general"));
colaTurnos.offer(new Solicitud("María", 1, "Emergencia crítica"));
colaTurnos.offer(new Solicitud("Pedro", 2, "Urgente moderado"));

// Atender en orden de prioridad
while (!colaTurnos.isEmpty()) {
    System.out.println("Atendiendo: " + colaTurnos.poll());
}
// Salida:
// Atendiendo: [P1] María → Emergencia crítica
// Atendiendo: [P2] Pedro → Urgente moderado
// Atendiendo: [P3] Juan → Consulta general
```

---

## 🗺️ HashMap — Registro y gestión de turnos

El HashMap es clave para **buscar un turno en O(1)** sin recorrer toda la cola.

### Casos de uso reales en sistemas de turnos

```java
import java.util.HashMap;
import java.util.Map;

public class GestorTurnos {

    // Mapa: ID de turno → Solicitud
    private HashMap<String, Solicitud> registroTurnos = new HashMap<>();

    // Mapa: tipo de solicitud → cantidad en espera
    private HashMap<String, Integer> contadorPorTipo = new HashMap<>();

    /**
     * Registrar un turno nuevo
     */
    public String registrarTurno(Solicitud solicitud) {
        String id = generarId(solicitud);
        registroTurnos.put(id, solicitud);

        // Actualizar contador del tipo
        contadorPorTipo.merge(solicitud.tipo, 1, Integer::sum);

        return id;
    }

    /**
     * Consultar turno por ID — O(1)
     */
    public Solicitud consultarTurno(String id) {
        return registroTurnos.getOrDefault(id, null);
    }

    /**
     * Cancelar turno
     */
    public boolean cancelarTurno(String id) {
        Solicitud s = registroTurnos.remove(id);
        if (s != null) {
            contadorPorTipo.merge(s.tipo, -1, Integer::sum);
            return true;
        }
        return false;
    }

    /**
     * Ver cuántos turnos hay por tipo
     */
    public void mostrarEstadisticas() {
        System.out.println("=== Estadísticas por tipo ===");
        for (Map.Entry<String, Integer> entry : contadorPorTipo.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue() + " en espera");
        }
    }

    private String generarId(Solicitud s) {
        return "T-" + System.currentTimeMillis() + "-" + s.prioridad;
    }
}
```

### Métodos HashMap más usados en este contexto

| Método | ¿Para qué? | Complejidad |
|--------|-----------|-------------|
| `put(k, v)` | Registrar turno nuevo | O(1) |
| `get(k)` | Buscar turno por ID | O(1) |
| `remove(k)` | Cancelar turno | O(1) |
| `containsKey(k)` | Verificar si existe | O(1) |
| `getOrDefault(k, v)` | Buscar con fallback | O(1) |
| `merge(k, v, fn)` | Actualizar contadores | O(1) |
| `entrySet()` | Iterar todos | O(n) |

---

## 🏥 Sistema Completo — Caso Real: Clínica de Atención

```java
import java.util.*;

public class SistemaClinica {

    // Prioridades definidas como constantes
    public static final int CRITICO   = 1;
    public static final int URGENTE   = 2;
    public static final int MODERADO  = 3;
    public static final int NORMAL    = 4;

    private PriorityQueue<Solicitud> cola;
    private HashMap<String, Solicitud> registro;
    private HashMap<Integer, String> etiquetas;
    private int contadorAtendidos;

    public SistemaClinica() {
        this.cola = new PriorityQueue<>(Comparator.comparingInt(s -> s.prioridad));
        this.registro = new HashMap<>();
        this.contadorAtendidos = 0;

        // HashMap para etiquetas legibles
        this.etiquetas = new HashMap<>();
        etiquetas.put(CRITICO,  "🔴 CRÍTICO");
        etiquetas.put(URGENTE,  "🟠 URGENTE");
        etiquetas.put(MODERADO, "🟡 MODERADO");
        etiquetas.put(NORMAL,   "🟢 NORMAL");
    }

    public String agregarPaciente(String nombre, int prioridad, String motivo) {
        Solicitud s = new Solicitud(nombre, prioridad, motivo);
        String id = "PAC-" + (registro.size() + 1);

        cola.offer(s);
        registro.put(id, s);

        System.out.println("✅ Turno asignado: " + id +
                           " | " + etiquetas.get(prioridad) +
                           " | " + nombre);
        return id;
    }

    public void atenderSiguiente() {
        if (cola.isEmpty()) {
            System.out.println("⚠️  No hay pacientes en espera.");
            return;
        }

        Solicitud siguiente = cola.poll();
        contadorAtendidos++;

        System.out.println("\n🏥 ATENDIENDO #" + contadorAtendidos);
        System.out.println("   Paciente : " + siguiente.nombre);
        System.out.println("   Prioridad: " + etiquetas.get(siguiente.prioridad));
        System.out.println("   Motivo   : " + siguiente.tipo);
        System.out.println("   En espera: " + cola.size() + " pacientes");
    }

    public void mostrarCola() {
        if (cola.isEmpty()) {
            System.out.println("Cola vacía.");
            return;
        }

        // Copiar para no destruir la cola original
        PriorityQueue<Solicitud> copia = new PriorityQueue<>(cola);
        System.out.println("\n📋 Cola actual (" + cola.size() + " pacientes):");
        int pos = 1;
        while (!copia.isEmpty()) {
            Solicitud s = copia.poll();
            System.out.println("  " + pos++ + ". " + etiquetas.get(s.prioridad) +
                               " - " + s.nombre + " (" + s.tipo + ")");
        }
    }

    public static void main(String[] args) {
        SistemaClinica clinica = new SistemaClinica();

        // Llegan pacientes en orden aleatorio
        clinica.agregarPaciente("Carlos",  NORMAL,    "Chequeo rutinario");
        clinica.agregarPaciente("Ana",     CRITICO,   "Infarto sospechoso");
        clinica.agregarPaciente("Luis",    MODERADO,  "Fiebre alta");
        clinica.agregarPaciente("Sandra",  URGENTE,   "Fractura de brazo");
        clinica.agregarPaciente("Miguel",  CRITICO,   "Pérdida de conciencia");

        clinica.mostrarCola();

        System.out.println("\n--- Iniciando atención ---");
        clinica.atenderSiguiente(); // Ana (crítico)
        clinica.atenderSiguiente(); // Miguel (crítico)
        clinica.atenderSiguiente(); // Sandra (urgente)

        clinica.mostrarCola();
    }
}
```

**Salida esperada:**
```
✅ Turno asignado: PAC-1 | 🟢 NORMAL | Carlos
✅ Turno asignado: PAC-2 | 🔴 CRÍTICO | Ana
...
📋 Cola actual (5 pacientes):
  1. 🔴 CRÍTICO - Ana (Infarto sospechoso)
  2. 🔴 CRÍTICO - Miguel (Pérdida de conciencia)
  3. 🟠 URGENTE - Sandra (Fractura de brazo)
  4. 🟡 MODERADO - Luis (Fiebre alta)
  5. 🟢 NORMAL - Carlos (Chequeo rutinario)
```

---

## ⚡ Comparación: Implementación Manual vs PriorityQueue de Java

| Aspecto | Manual (Nodos) | PriorityQueue nativa |
|---------|---------------|---------------------|
| Control | Total | Limitado |
| Rendimiento | Inserción O(n) | Inserción O(log n) |
| Código | Más extenso | Compacto |
| Aprendizaje | Ideal para entender la base | Uso en producción |
| Personalización | Sin límites | Requiere Comparator |

**Regla práctica:** Aprende la versión manual para entender la lógica → usa `PriorityQueue` en proyectos reales.

---

## 🔑 Patrones frecuentes en exámenes y entrevistas

### 1. Cola de dos niveles (VIP + Normal)
```java
Queue<Solicitud> colaVip    = new LinkedList<>();
Queue<Solicitud> colaNormal = new LinkedList<>();

// Atender: siempre VIP primero
Solicitud atender() {
    if (!colaVip.isEmpty())    return colaVip.poll();
    if (!colaNormal.isEmpty()) return colaNormal.poll();
    return null;
}
```

### 2. Verificar si un ID ya está registrado
```java
if (registro.containsKey(id)) {
    System.out.println("Ya existe el turno " + id);
} else {
    registro.put(id, nuevaSolicitud);
}
```

### 3. Contar solicitudes por categoría con HashMap
```java
HashMap<String, Long> conteo = solicitudes.stream()
    .collect(Collectors.groupingBy(
        s -> s.tipo,
        Collectors.counting()
    ));
```

---

## 📐 Diagrama mental: Flujo de una solicitud

```
Cliente llega
      │
      ▼
┌─────────────┐
│  Asignar    │  → HashMap registra ID + datos
│  prioridad  │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Encolar    │  → PriorityQueue reordena automáticamente
│  en cola    │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  poll()     │  → Saca el de mayor prioridad
│  Atender    │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  HashMap    │  → remove(id) limpia el registro
│  actualiza  │
└─────────────┘
```

---

## 💡 Tips clave para no olvidar

- **HashMap vs ArrayList para búsqueda:** Si vas a buscar por ID frecuentemente, siempre HashMap. ArrayList es O(n), HashMap es O(1).
- **PriorityQueue no garantiza orden en iteración**, solo garantiza que `poll()` devuelve el mínimo/máximo.
- **Copia la cola antes de iterar:** `new PriorityQueue<>(colaOriginal)` — si haces `poll()` directo, destruyes la cola.
- **`merge()` es tu mejor amigo para contadores:** evita el clásico `if containsKey → get → +1 → put`.
- **Prioridad 1 antes que 2:** en sistemas reales, número menor = mayor urgencia. Confírmalo siempre con tu Comparator.

---

*Guía generada para estudio de estructuras de datos en Java — enfoque en sistemas de turnos y atención con prioridades.*