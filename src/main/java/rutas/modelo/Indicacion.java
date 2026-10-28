package rutas.modelo;

/** Un paso de las indicaciones: "Gire a la derecha en Calle 15 y siga 300 m". */
public class Indicacion {

    private final String texto;
    private final double metros;
    private final int nodo; // donde empieza el paso, para ubicarlo en el mapa
    private final boolean esEntrega;

    public Indicacion(String texto, double metros, int nodo, boolean esEntrega) {
        this.texto = texto;
        this.metros = metros;
        this.nodo = nodo;
        this.esEntrega = esEntrega;
    }

    public String getTexto() { return texto; }
    public double getMetros() { return metros; }
    public int getNodo() { return nodo; }
    public boolean isEsEntrega() { return esEntrega; }

    @Override
    public String toString() {
        return texto;
    }
}
