package rutas.estructuras;

/**
 * Nodo del arbol AVL. Guarda una clave (para ordenar) y un valor.
 */
public class NodoAVL<K extends Comparable<K>, V> {

    K clave;
    V valor;
    int altura;
    NodoAVL<K, V> izq;
    NodoAVL<K, V> der;

    public NodoAVL(K clave, V valor) {
        this.clave = clave;
        this.valor = valor;
        this.altura = 1; // un nodo hoja tiene altura 1
    }

    public K getClave() {
        return clave;
    }

    public V getValor() {
        return valor;
    }
}
