package rutas.estructuras;

/**
 * Cola de enteros con nodos enlazados (FIFO). La usa el recorrido BFS.
 */
public class Cola {

    private static class Nodo {
        int dato;
        Nodo sig;

        Nodo(int dato) {
            this.dato = dato;
        }
    }

    private Nodo frente;
    private Nodo fin;
    private int tamano;

    public void encolar(int dato) {
        Nodo n = new Nodo(dato);
        if (fin == null) {
            frente = n;
        } else {
            fin.sig = n;
        }
        fin = n;
        tamano++;
    }

    public int desencolar() {
        if (frente == null) {
            throw new IllegalStateException("La cola esta vacia");
        }
        int dato = frente.dato;
        frente = frente.sig;
        if (frente == null) {
            fin = null;
        }
        tamano--;
        return dato;
    }

    public boolean estaVacia() {
        return frente == null;
    }

    public int getTamano() {
        return tamano;
    }
}
