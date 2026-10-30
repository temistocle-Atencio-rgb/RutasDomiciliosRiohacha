package rutas.logica;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import rutas.estructuras.Arista;
import rutas.estructuras.Grafo;
import rutas.estructuras.MonticuloMin;

/**
 * Algoritmo de Dijkstra con monticulo de minimos (cola de prioridad).
 * Complejidad O((V + E) log V), que en la red de Riohacha (11.000 nodos)
 * tarda unos pocos milisegundos.
 *
 * Segun el criterio, el peso de cada arista es:
 *  - MAS_CORTA: los metros del tramo (con lluvia, las destapadas cuentan mas largas para evitarlas).
 *  - MAS_RAPIDA: los segundos que calcula el ModeloTrafico.
 * En los dos casos se lleva la cuenta de metros y segundos para mostrarlos.
 */
public class Dijkstra {

    private final Grafo grafo;
    private final int origen;
    private final double[] costo;
    private final double[] metros;
    private final double[] segundos;
    private final Arista[] llegaPor; // arista por la que se llego a cada nodo (para reconstruir el camino)
    private final int[] ordenVisita;  // nodos en el orden en que el algoritmo los fue cerrando
    private int visitados;

    public Dijkstra(Grafo grafo, int origen, ModeloTrafico trafico, Criterio criterio, LocalTime hora) {
        this.grafo = grafo;
        this.origen = origen;
        int n = grafo.getCantidadNodos();
        costo = new double[n];
        metros = new double[n];
        segundos = new double[n];
        llegaPor = new Arista[n];
        ordenVisita = new int[n];
        Arrays.fill(costo, Double.POSITIVE_INFINITY);
        boolean[] listo = new boolean[n];

        costo[origen] = 0;
        MonticuloMin cola = new MonticuloMin(1024);
        cola.insertar(origen, 0);

        while (!cola.estaVacio()) {
            int u = cola.extraerMinimo();
            if (listo[u]) {
                continue; // entrada vieja del monticulo
            }
            listo[u] = true;
            ordenVisita[visitados++] = u;

            for (Arista a = grafo.primera(u); a != null; a = a.getSiguiente()) {
                int v = a.getDestino();
                if (listo[v] || trafico.estaCerrada(a)) {
                    continue;
                }
                double seg = trafico.segundos(a, hora);
                double peso = criterio == Criterio.MAS_CORTA ? trafico.pesoDistancia(a) : seg;
                double nuevo = costo[u] + peso;
                if (nuevo < costo[v]) { // relajacion
                    costo[v] = nuevo;
                    metros[v] = metros[u] + a.getMetros();
                    segundos[v] = segundos[u] + seg;
                    llegaPor[v] = a;
                    cola.insertar(v, nuevo);
                }
            }
        }
    }

    /**
     * Esquinas en el orden en que Dijkstra las visito, hasta llegar al destino.
     * Sirve para mostrar en el mapa como el algoritmo "busca" el camino: se expande
     * como una onda desde el origen, mas rapido por las vias rapidas.
     */
    public int[] ordenDeVisitaHasta(int destino) {
        for (int i = 0; i < visitados; i++) {
            if (ordenVisita[i] == destino) {
                return Arrays.copyOf(ordenVisita, i + 1);
            }
        }
        return Arrays.copyOf(ordenVisita, visitados);
    }

    public boolean hayCamino(int destino) {
        return costo[destino] != Double.POSITIVE_INFINITY;
    }

    public double getCosto(int destino) {
        return costo[destino];
    }

    public double getMetros(int destino) {
        return metros[destino];
    }

    public double getSegundos(int destino) {
        return segundos[destino];
    }

    public int getOrigen() {
        return origen;
    }

    /** Aristas del camino desde el origen hasta el destino (vacio si no hay). */
    public List<Arista> camino(int destino) {
        List<Arista> lista = new ArrayList<>();
        if (!hayCamino(destino)) {
            return lista;
        }
        for (int v = destino; v != origen; v = llegaPor[v].getOrigen()) {
            lista.add(llegaPor[v]);
        }
        Collections.reverse(lista);
        return lista;
    }

    public Grafo getGrafo() {
        return grafo;
    }
}
