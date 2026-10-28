package rutas.estructuras;

import java.util.Arrays;

/**
 * Monticulo binario de minimos (cola de prioridad) hecho a mano.
 * Guarda pares (nodo, prioridad) y siempre saca el de menor prioridad.
 * Dijkstra lo usa para tomar el nodo mas cercano en O(log n)
 * en vez de recorrer todos los nodos cada vez.
 *
 * Se guarda en un arreglo: los hijos de la posicion i estan en 2i+1 y 2i+2,
 * y el padre en (i-1)/2.
 */
public class MonticuloMin {

    private int[] nodos;
    private double[] prioridades;
    private int tamano;

    public MonticuloMin(int capacidadInicial) {
        nodos = new int[Math.max(4, capacidadInicial)];
        prioridades = new double[nodos.length];
    }

    public boolean estaVacio() {
        return tamano == 0;
    }

    public int getTamano() {
        return tamano;
    }

    /** Inserta y sube el elemento hasta su lugar. O(log n) */
    public void insertar(int nodo, double prioridad) {
        if (tamano == nodos.length) {
            nodos = Arrays.copyOf(nodos, tamano * 2);
            prioridades = Arrays.copyOf(prioridades, tamano * 2);
        }
        int i = tamano++;
        nodos[i] = nodo;
        prioridades[i] = prioridad;
        while (i > 0) {
            int padre = (i - 1) / 2;
            if (prioridades[padre] <= prioridades[i]) {
                break;
            }
            intercambiar(i, padre);
            i = padre;
        }
    }

    public double prioridadMinima() {
        return prioridades[0];
    }

    public int nodoMinimo() {
        return nodos[0];
    }

    /** Saca el de menor prioridad y devuelve el nodo. O(log n) */
    public int extraerMinimo() {
        int min = nodos[0];
        tamano--;
        nodos[0] = nodos[tamano];
        prioridades[0] = prioridades[tamano];
        int i = 0;
        while (true) {
            int izq = 2 * i + 1, der = 2 * i + 2, menor = i;
            if (izq < tamano && prioridades[izq] < prioridades[menor]) {
                menor = izq;
            }
            if (der < tamano && prioridades[der] < prioridades[menor]) {
                menor = der;
            }
            if (menor == i) {
                break;
            }
            intercambiar(i, menor);
            i = menor;
        }
        return min;
    }

    private void intercambiar(int a, int b) {
        int n = nodos[a];
        nodos[a] = nodos[b];
        nodos[b] = n;
        double p = prioridades[a];
        prioridades[a] = prioridades[b];
        prioridades[b] = p;
    }
}
