package rutas.estructuras;

import java.util.Arrays;

/**
 * Matriz de adyacencia entre las paradas de una ruta (base + entregas).
 * La celda [i][j] guarda el costo del mejor camino de la parada i a la parada j
 * calculado con Dijkstra sobre la red vial. Con esta matriz el planificador
 * decide el orden de las entregas sin volver a recorrer toda la ciudad.
 * No es simetrica: por las calles de una via, ir de A a B puede costar
 * diferente que ir de B a A.
 */
public class MatrizAdyacencia {

    public static final double SIN_CAMINO = Double.POSITIVE_INFINITY;

    private final double[][] costo;

    public MatrizAdyacencia(int n) {
        costo = new double[n][n];
        for (double[] fila : costo) {
            Arrays.fill(fila, SIN_CAMINO);
        }
        for (int i = 0; i < n; i++) {
            costo[i][i] = 0;
        }
    }

    public void set(int i, int j, double valor) {
        costo[i][j] = valor;
    }

    public double get(int i, int j) {
        return costo[i][j];
    }

    public boolean hayCamino(int i, int j) {
        return costo[i][j] != SIN_CAMINO;
    }

    public int tamano() {
        return costo.length;
    }

    /** Costo de recorrer las paradas en el orden dado. */
    public double costoRecorrido(int[] orden, boolean volverAlInicio) {
        double total = 0;
        for (int k = 0; k < orden.length - 1; k++) {
            total += costo[orden[k]][orden[k + 1]];
        }
        if (volverAlInicio && orden.length > 1) {
            total += costo[orden[orden.length - 1]][orden[0]];
        }
        return total;
    }
}
