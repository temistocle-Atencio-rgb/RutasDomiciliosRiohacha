package rutas.modelo;

/** Lugar donde se guarda mercancia. La 1 es el punto de venta: de ahi sale lo que se vende. */
public class Bodega {

    public static final long PUNTO_DE_VENTA = 1;

    private final long id;
    private final String nombre;

    public Bodega(long id, String nombre) {
        this.id = id;
        this.nombre = nombre == null ? "" : nombre.trim();
    }

    public long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public boolean esPuntoDeVenta() {
        return id == PUNTO_DE_VENTA;
    }

    @Override
    public String toString() {
        return nombre;
    }
}
