package rutas.modelo;

import java.time.LocalDateTime;

/**
 * Un repartidor dijo «La casa no está aquí» y movió el pin. Si coincidía con su GPS ya viene aplicada;
 * si no, queda pendiente para que la tienda la apruebe o la descarte.
 */
public class Correccion {
    public static final String APLICADA = "aplicada";
    public static final String PENDIENTE = "pendiente";
    public static final String DESCARTADA = "descartada";

    public long idNube;
    public String nombre = "";
    public String direccion = "";
    public double latViejo = Double.NaN;
    public double lonViejo = Double.NaN;
    public double lat;
    public double lon;
    public int distanciaM;
    public String estado = PENDIENTE;
    public String repartidor = "";
    public LocalDateTime creada = LocalDateTime.now();

    public boolean pendiente() {
        return PENDIENTE.equals(estado);
    }
}
