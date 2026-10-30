package rutas.modelo;

/**
 * Un punto de la ruta: la base de donde sale el repartidor o una entrega.
 * nodo es el nodo del grafo mas cercano a la direccion.
 */
public class Parada {

    private final String nombre;
    private final String direccion;
    private final double lat;
    private final double lon;
    private int nodo = -1;
    private String precision = "";
    private boolean urgente; // se entrega antes que las demas
    private String telefono = ""; // celular del cliente, para avisarle
    private int valorPedido;      // pesos; 0 si no se sabe
    private boolean pagado;       // ya pago por transferencia

    public Parada(String nombre, String direccion, double lat, double lon) {
        this.nombre = nombre;
        this.direccion = direccion;
        this.lat = lat;
        this.lon = lon;
    }

    public String getNombre() { return nombre; }
    public String getDireccion() { return direccion; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public int getNodo() { return nodo; }
    public void setNodo(int nodo) { this.nodo = nodo; }
    public boolean isUrgente() { return urgente; }
    public void setUrgente(boolean urgente) { this.urgente = urgente; }
    public int getValorPedido() { return valorPedido; }
    public void setValorPedido(int valorPedido) { this.valorPedido = Math.max(0, valorPedido); }
    public boolean isPagado() { return pagado; }
    public void setPagado(boolean pagado) { this.pagado = pagado; }

    /** Copia lo que el negocio escribio del pedido (urgente, celular, valor, pago) a otra parada. */
    public void copiarDatosDe(Parada otra) {
        urgente = otra.urgente;
        telefono = otra.telefono;
        valorPedido = otra.valorPedido;
        pagado = otra.pagado;
    }

    public String getTelefono() { return telefono; }
    public void setTelefono(String telefono) { this.telefono = telefono == null ? "" : telefono; }
    public String getPrecision() { return precision; }
    public void setPrecision(String precision) { this.precision = precision; }

    /** Nombre corto para listas. */
    public String etiqueta() {
        if (nombre.isEmpty() || nombre.equalsIgnoreCase(direccion)) {
            return direccion;
        }
        return nombre + " — " + direccion;
    }

    @Override
    public String toString() {
        return etiqueta();
    }
}
