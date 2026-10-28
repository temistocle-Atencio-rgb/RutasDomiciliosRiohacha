package rutas.estructuras;

/**
 * Arista del grafo: un pedazo de calle entre dos nodos seguidos.
 * Las aristas que salen de un nodo forman una lista enlazada (campo sig).
 */
public class Arista {

    final int origen;
    final int destino;
    final double metros;
    final int via;   // indice del tramo de via al que pertenece
    Arista sig;      // siguiente arista en la lista de adyacencia del nodo origen

    Arista(int origen, int destino, double metros, int via) {
        this.origen = origen;
        this.destino = destino;
        this.metros = metros;
        this.via = via;
    }

    public int getOrigen() { return origen; }
    public int getDestino() { return destino; }
    public double getMetros() { return metros; }
    public int getVia() { return via; }
    public Arista getSiguiente() { return sig; }
}
