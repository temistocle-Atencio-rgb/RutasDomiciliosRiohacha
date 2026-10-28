package rutas.modelo;

/** Un sitio con nombre: restaurante, droguería, barrio, colegio, etc. */
public class Lugar {

    private final int id;
    private final String nombre;
    private final CategoriaLugar categoria;
    private final double lat;
    private final double lon;

    public Lugar(int id, String nombre, CategoriaLugar categoria, double lat, double lon) {
        this.id = id;
        this.nombre = nombre;
        this.categoria = categoria;
        this.lat = lat;
        this.lon = lon;
    }

    public int getId() { return id; }
    public String getNombre() { return nombre; }
    public CategoriaLugar getCategoria() { return categoria; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }

    @Override
    public String toString() {
        return nombre + "  (" + categoria + ")";
    }
}
