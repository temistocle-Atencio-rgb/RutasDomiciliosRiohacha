package rutas.estructuras;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import rutas.modelo.Geo;

/**
 * Grafo dirigido y ponderado de la red vial de Riohacha.
 *
 * Nodos: esquinas y puntos de las calles (vienen de OpenStreetMap).
 * Aristas: pedazos de calle entre dos nodos, con su distancia en metros.
 * Las calles de doble via tienen una arista en cada sentido; las de una via, solo una.
 *
 * Se usa LISTA DE ADYACENCIA porque la red tiene unos 11.000 nodos y cada
 * esquina se conecta con 2 a 4 vecinos: una matriz de 11.000 x 11.000 ocuparia
 * cerca de 1 GB de memoria y casi toda estaria vacia. La matriz de adyacencia
 * se usa en otro nivel: entre las paradas de la ruta (ver MatrizAdyacencia).
 */
public class Grafo {

    private final int n;
    private final double[] lat;
    private final double[] lon;
    private final double[] x;
    private final double[] y;
    private final char[] marca;       // S = semaforo, P = pare, R = reductor, - = nada
    private final Arista[] cabeza;    // primera arista de la lista de cada nodo
    private final int[] gradoSalida;
    private int cantidadAristas;

    // indice espacial: celdas de 150 m para buscar el nodo mas cercano rapido
    private static final double CELDA = 150;
    private final Map<Long, List<Integer>> celdas = new HashMap<>();

    public Grafo(int cantidadNodos) {
        n = cantidadNodos;
        lat = new double[n];
        lon = new double[n];
        x = new double[n];
        y = new double[n];
        marca = new char[n];
        cabeza = new Arista[n];
        gradoSalida = new int[n];
    }

    public void setNodo(int i, double latitud, double longitud, char m) {
        lat[i] = latitud;
        lon[i] = longitud;
        x[i] = Geo.x(longitud);
        y[i] = Geo.y(latitud);
        marca[i] = m;
        celdas.computeIfAbsent(claveCelda(x[i], y[i]), k -> new ArrayList<>()).add(i);
    }

    /** Agrega la arista al inicio de la lista enlazada del nodo origen. O(1) */
    public void agregarArista(int origen, int destino, double metros, int via) {
        Arista a = new Arista(origen, destino, metros, via);
        a.sig = cabeza[origen];
        cabeza[origen] = a;
        gradoSalida[origen]++;
        cantidadAristas++;
    }

    /** Primera arista que sale del nodo. Para recorrer: for (a = primera(u); a != null; a = a.getSiguiente()) */
    public Arista primera(int nodo) {
        return cabeza[nodo];
    }

    public Arista buscarArista(int origen, int destino) {
        for (Arista a = cabeza[origen]; a != null; a = a.sig) {
            if (a.destino == destino) {
                return a;
            }
        }
        return null;
    }

    // ---------------- BFS ----------------

    /**
     * Recorrido en anchura desde un nodo respetando el sentido de las calles.
     * Devuelve un arreglo que dice a que nodos se puede llegar.
     * Se usa para saber si una direccion queda en una zona sin salida.
     */
    public boolean[] bfs(int origen) {
        boolean[] visitado = new boolean[n];
        Cola cola = new Cola();
        visitado[origen] = true;
        cola.encolar(origen);
        while (!cola.estaVacia()) {
            int u = cola.desencolar();
            for (Arista a = cabeza[u]; a != null; a = a.sig) {
                if (!visitado[a.destino]) {
                    visitado[a.destino] = true;
                    cola.encolar(a.destino);
                }
            }
        }
        return visitado;
    }

    // aristas al reves (quien llega a cada nodo), se arman solo si se necesitan
    private int[] inicioInverso;
    private int[] origenesInverso;

    private void armarInverso() {
        inicioInverso = new int[n + 1];
        for (int u = 0; u < n; u++) {
            for (Arista a = cabeza[u]; a != null; a = a.sig) {
                inicioInverso[a.destino + 1]++;
            }
        }
        for (int i = 0; i < n; i++) {
            inicioInverso[i + 1] += inicioInverso[i];
        }
        origenesInverso = new int[cantidadAristas];
        int[] pos = java.util.Arrays.copyOf(inicioInverso, n);
        for (int u = 0; u < n; u++) {
            for (Arista a = cabeza[u]; a != null; a = a.sig) {
                origenesInverso[pos[a.destino]++] = u;
            }
        }
    }

    /** BFS con las calles al reves: nodos DESDE los que se puede llegar al destino. */
    public boolean[] bfsInverso(int destino) {
        if (inicioInverso == null) {
            armarInverso();
        }
        boolean[] visitado = new boolean[n];
        Cola cola = new Cola();
        visitado[destino] = true;
        cola.encolar(destino);
        while (!cola.estaVacia()) {
            int v = cola.desencolar();
            for (int k = inicioInverso[v]; k < inicioInverso[v + 1]; k++) {
                int u = origenesInverso[k];
                if (!visitado[u]) {
                    visitado[u] = true;
                    cola.encolar(u);
                }
            }
        }
        return visitado;
    }

    /** Nodos a los que se puede ir desde la base y volver a ella (componente fuertemente conexa). */
    public boolean[] idaYVuelta(int base) {
        boolean[] ida = bfs(base);
        boolean[] vuelta = bfsInverso(base);
        for (int i = 0; i < n; i++) {
            ida[i] &= vuelta[i];
        }
        return ida;
    }

    /** Version de BFS que cuenta en cuantos "saltos" se llega a cada nodo (-1 si no se llega). */
    public int[] bfsNiveles(int origen) {
        int[] nivel = new int[n];
        java.util.Arrays.fill(nivel, -1);
        Cola cola = new Cola();
        nivel[origen] = 0;
        cola.encolar(origen);
        while (!cola.estaVacia()) {
            int u = cola.desencolar();
            for (Arista a = cabeza[u]; a != null; a = a.sig) {
                if (nivel[a.destino] == -1) {
                    nivel[a.destino] = nivel[u] + 1;
                    cola.encolar(a.destino);
                }
            }
        }
        return nivel;
    }

    // ---------------- busqueda espacial ----------------

    private static long claveCelda(double px, double py) {
        long cx = (long) Math.floor(px / CELDA);
        long cy = (long) Math.floor(py / CELDA);
        return (cx << 32) ^ (cy & 0xffffffffL);
    }

    /** Nodo mas cercano a un punto (en metros del plano). filtro puede ser null. */
    public int nodoMasCercano(double px, double py, boolean[] filtro) {
        int mejor = -1;
        double mejorD = Double.MAX_VALUE;
        long cx = (long) Math.floor(px / CELDA);
        long cy = (long) Math.floor(py / CELDA);
        for (int radio = 0; radio < 40; radio++) {
            for (long i = cx - radio; i <= cx + radio; i++) {
                for (long j = cy - radio; j <= cy + radio; j++) {
                    if (Math.max(Math.abs(i - cx), Math.abs(j - cy)) != radio) {
                        continue; // solo el borde del cuadrado de este radio
                    }
                    List<Integer> lista = celdas.get((i << 32) ^ (j & 0xffffffffL));
                    if (lista == null) {
                        continue;
                    }
                    for (int v : lista) {
                        if (filtro != null && !filtro[v]) {
                            continue;
                        }
                        double d = Math.hypot(x[v] - px, y[v] - py);
                        if (d < mejorD) {
                            mejorD = d;
                            mejor = v;
                        }
                    }
                }
            }
            // si ya hay uno y el siguiente anillo esta mas lejos, se para
            if (mejor != -1 && mejorD < radio * CELDA) {
                break;
            }
        }
        return mejor;
    }

    public double distanciaPlano(int a, int b) {
        return Math.hypot(x[a] - x[b], y[a] - y[b]);
    }

    // ---------------- getters ----------------

    public int getCantidadNodos() { return n; }
    public int getCantidadAristas() { return cantidadAristas; }
    public double getLat(int i) { return lat[i]; }
    public double getLon(int i) { return lon[i]; }
    public double getX(int i) { return x[i]; }
    public double getY(int i) { return y[i]; }
    public char getMarca(int i) { return marca[i]; }
    public int getGradoSalida(int i) { return gradoSalida[i]; }
}
