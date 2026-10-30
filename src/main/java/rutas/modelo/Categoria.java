package rutas.modelo;

/** Grupo de productos (Dulces, Granos, Aseo...) con su foto para la caja. */
public class Categoria {

    private final long id;
    private final String nombre;
    private final String foto;
    private final int orden;
    private final boolean activo;

    public Categoria(long id, String nombre, String foto, int orden, boolean activo) {
        this.id = id;
        this.nombre = nombre == null ? "" : nombre.trim();
        this.foto = foto;
        this.orden = orden;
        this.activo = activo;
    }

    public long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getFoto() {
        return foto;
    }

    public int getOrden() {
        return orden;
    }

    public boolean isActivo() {
        return activo;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Categoria && ((Categoria) o).id == id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return nombre;
    }
}
