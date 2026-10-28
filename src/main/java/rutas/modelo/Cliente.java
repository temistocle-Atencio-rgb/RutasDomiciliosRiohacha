package rutas.modelo;

/** Cliente guardado para no tener que escribir su direccion cada vez. */
public class Cliente {

    private final String nombre;
    private final String telefono;
    private final String direccion;
    private final String nota;
    private final double lat;
    private final double lon;

    public Cliente(String nombre, String telefono, String direccion, String nota, double lat, double lon) {
        this.nombre = nombre;
        this.telefono = telefono;
        this.direccion = direccion;
        this.nota = nota;
        this.lat = lat;
        this.lon = lon;
    }

    public String getNombre() { return nombre; }
    public String getTelefono() { return telefono; }
    public String getDireccion() { return direccion; }
    public String getNota() { return nota; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }

    @Override
    public String toString() {
        return nombre + " - " + direccion;
    }
}
