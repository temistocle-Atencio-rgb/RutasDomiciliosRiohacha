package rutas.estructuras;

import java.util.ArrayList;
import java.util.List;

/**
 * Arbol AVL generico (clave -> valor).
 * En el proyecto se usa para:
 *  - el indice de calles y carreras por nombre y por numero,
 *  - el buscador de lugares (restaurantes, droguerias, barrios...),
 *  - los clientes guardados ordenados por nombre.
 * Insertar, buscar y eliminar son O(log n) porque el arbol se mantiene balanceado.
 */
public class ArbolAVL<K extends Comparable<K>, V> {

    private NodoAVL<K, V> raiz;
    private int tamano;

    // ---------------- utilidades ----------------

    private int altura(NodoAVL<K, V> n) {
        return n == null ? 0 : n.altura;
    }

    private int factorBalance(NodoAVL<K, V> n) {
        return n == null ? 0 : altura(n.izq) - altura(n.der);
    }

    private void actualizarAltura(NodoAVL<K, V> n) {
        n.altura = 1 + Math.max(altura(n.izq), altura(n.der));
    }

    // ---------------- rotaciones ----------------

    //       y              x
    //      / \            / \
    //     x   T3   =>   T1   y
    //    / \                / \
    //   T1  T2             T2  T3
    private NodoAVL<K, V> rotarDerecha(NodoAVL<K, V> y) {
        NodoAVL<K, V> x = y.izq;
        NodoAVL<K, V> t2 = x.der;
        x.der = y;
        y.izq = t2;
        actualizarAltura(y);
        actualizarAltura(x);
        return x;
    }

    private NodoAVL<K, V> rotarIzquierda(NodoAVL<K, V> x) {
        NodoAVL<K, V> y = x.der;
        NodoAVL<K, V> t2 = y.izq;
        y.izq = x;
        x.der = t2;
        actualizarAltura(x);
        actualizarAltura(y);
        return y;
    }

    // revisa el balance de un nodo y hace la rotacion que toque (LL, RR, LR, RL)
    private NodoAVL<K, V> balancear(NodoAVL<K, V> n) {
        actualizarAltura(n);
        int fb = factorBalance(n);

        if (fb > 1) { // cargado a la izquierda
            if (factorBalance(n.izq) < 0) {
                n.izq = rotarIzquierda(n.izq); // caso LR
            }
            return rotarDerecha(n); // caso LL
        }
        if (fb < -1) { // cargado a la derecha
            if (factorBalance(n.der) > 0) {
                n.der = rotarDerecha(n.der); // caso RL
            }
            return rotarIzquierda(n); // caso RR
        }
        return n;
    }

    // ---------------- insertar ----------------

    /** Inserta la clave. Si ya existe, reemplaza el valor. */
    public void insertar(K clave, V valor) {
        raiz = insertar(raiz, clave, valor);
    }

    private NodoAVL<K, V> insertar(NodoAVL<K, V> n, K clave, V valor) {
        if (n == null) {
            tamano++;
            return new NodoAVL<>(clave, valor);
        }
        int cmp = clave.compareTo(n.clave);
        if (cmp < 0) {
            n.izq = insertar(n.izq, clave, valor);
        } else if (cmp > 0) {
            n.der = insertar(n.der, clave, valor);
        } else {
            n.valor = valor; // clave repetida
            return n;
        }
        return balancear(n);
    }

    // ---------------- buscar ----------------

    public V buscar(K clave) {
        NodoAVL<K, V> actual = raiz;
        while (actual != null) {
            int cmp = clave.compareTo(actual.clave);
            if (cmp == 0) {
                return actual.valor;
            }
            actual = cmp < 0 ? actual.izq : actual.der;
        }
        return null;
    }

    public boolean contiene(K clave) {
        return buscar(clave) != null;
    }

    /**
     * Piso: el nodo con la clave mas grande que sea menor o igual a la dada.
     * Se usa para encontrar la calle anterior cuando una calle no esta en el mapa.
     */
    public NodoAVL<K, V> piso(K clave) {
        NodoAVL<K, V> actual = raiz, mejor = null;
        while (actual != null) {
            int cmp = clave.compareTo(actual.clave);
            if (cmp == 0) {
                return actual;
            }
            if (cmp > 0) {
                mejor = actual;
                actual = actual.der;
            } else {
                actual = actual.izq;
            }
        }
        return mejor;
    }

    /** Techo: el nodo con la clave mas pequena que sea mayor o igual a la dada. */
    public NodoAVL<K, V> techo(K clave) {
        NodoAVL<K, V> actual = raiz, mejor = null;
        while (actual != null) {
            int cmp = clave.compareTo(actual.clave);
            if (cmp == 0) {
                return actual;
            }
            if (cmp < 0) {
                mejor = actual;
                actual = actual.izq;
            } else {
                actual = actual.der;
            }
        }
        return mejor;
    }

    // ---------------- eliminar ----------------

    public void eliminar(K clave) {
        raiz = eliminar(raiz, clave);
    }

    private NodoAVL<K, V> eliminar(NodoAVL<K, V> n, K clave) {
        if (n == null) {
            return null;
        }
        int cmp = clave.compareTo(n.clave);
        if (cmp < 0) {
            n.izq = eliminar(n.izq, clave);
        } else if (cmp > 0) {
            n.der = eliminar(n.der, clave);
        } else {
            // encontrado
            if (n.izq == null || n.der == null) {
                tamano--;
                return n.izq != null ? n.izq : n.der;
            }
            // tiene dos hijos: se reemplaza por el menor del subarbol derecho
            NodoAVL<K, V> sucesor = n.der;
            while (sucesor.izq != null) {
                sucesor = sucesor.izq;
            }
            n.clave = sucesor.clave;
            n.valor = sucesor.valor;
            n.der = eliminar(n.der, sucesor.clave);
        }
        return balancear(n);
    }

    // ---------------- recorridos ----------------

    /** Recorrido inorden: devuelve los valores ordenados por clave. */
    public List<V> enOrden() {
        List<V> lista = new ArrayList<>();
        enOrden(raiz, lista);
        return lista;
    }

    private void enOrden(NodoAVL<K, V> n, List<V> lista) {
        if (n == null) {
            return;
        }
        enOrden(n.izq, lista);
        lista.add(n.valor);
        enOrden(n.der, lista);
    }

    /** Recorrido inorden devolviendo los nodos (clave y valor). */
    public List<NodoAVL<K, V>> nodosEnOrden() {
        List<NodoAVL<K, V>> lista = new ArrayList<>();
        nodosEnOrden(raiz, lista);
        return lista;
    }

    private void nodosEnOrden(NodoAVL<K, V> n, List<NodoAVL<K, V>> lista) {
        if (n == null) {
            return;
        }
        nodosEnOrden(n.izq, lista);
        lista.add(n);
        nodosEnOrden(n.der, lista);
    }

    /**
     * Busca los valores cuya clave empieza por el prefijo (para claves String).
     * Se poda el arbol: solo se baja por las ramas que pueden tener coincidencias.
     * @param limite maximo de resultados (0 = sin limite)
     */
    public List<V> buscarPorPrefijo(String prefijo, int limite) {
        List<V> lista = new ArrayList<>();
        buscarPorPrefijo(raiz, prefijo, lista, limite);
        return lista;
    }

    public List<V> buscarPorPrefijo(String prefijo) {
        return buscarPorPrefijo(prefijo, 0);
    }

    private void buscarPorPrefijo(NodoAVL<K, V> n, String prefijo, List<V> lista, int limite) {
        if (n == null || (limite > 0 && lista.size() >= limite)) {
            return;
        }
        String clave = n.clave.toString();
        String inicio = clave.length() > prefijo.length() ? clave.substring(0, prefijo.length()) : clave;
        int cmp = prefijo.compareTo(inicio);

        if (cmp <= 0) { // puede haber coincidencias a la izquierda
            buscarPorPrefijo(n.izq, prefijo, lista, limite);
        }
        if (clave.startsWith(prefijo) && (limite == 0 || lista.size() < limite)) {
            lista.add(n.valor);
        }
        if (cmp >= 0) { // puede haber coincidencias a la derecha
            buscarPorPrefijo(n.der, prefijo, lista, limite);
        }
    }

    // ---------------- info ----------------

    public int getTamano() {
        return tamano;
    }

    public int getAltura() {
        return altura(raiz);
    }

    public boolean estaVacio() {
        return raiz == null;
    }

    /** Revisa que todo el arbol cumpla la propiedad AVL (para las pruebas). */
    public boolean estaBalanceado() {
        return revisar(raiz) != -1;
    }

    private int revisar(NodoAVL<K, V> n) {
        if (n == null) {
            return 0;
        }
        int hi = revisar(n.izq);
        int hd = revisar(n.der);
        if (hi == -1 || hd == -1 || Math.abs(hi - hd) > 1) {
            return -1;
        }
        return 1 + Math.max(hi, hd);
    }
}
