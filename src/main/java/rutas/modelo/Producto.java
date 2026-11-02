package rutas.modelo;

/**
 * Producto de la tienda. La plata va en pesos enteros y las cantidades en
 * milesimas (1,5 libras = 1500) para que las cuentas nunca den decimales raros.
 * No se modifica: para editar se crea otro Producto con el mismo id.
 */
public class Producto {

    private final long id;
    private final String codigo;
    private final String nombre;
    private final Unidad unidad;
    private final long precioVenta;
    private final long precioCompra;
    private final long stock;
    private final long minimo;
    private final String foto;
    private final boolean activo;
    private final Long categoriaId; // puede no tener categoria
    private final boolean manejaInventario; // false: se vende sin contar existencias (servicios, recetas)

    public Producto(long id, String codigo, String nombre, Unidad unidad, long precioVenta, long precioCompra,
            long stock, long minimo, String foto, boolean activo) {
        this(id, codigo, nombre, unidad, precioVenta, precioCompra, stock, minimo, foto, activo, null);
    }

    public Producto(long id, String codigo, String nombre, Unidad unidad, long precioVenta, long precioCompra,
            long stock, long minimo, String foto, boolean activo, Long categoriaId) {
        this(id, codigo, nombre, unidad, precioVenta, precioCompra, stock, minimo, foto, activo, categoriaId, true);
    }

    public Producto(long id, String codigo, String nombre, Unidad unidad, long precioVenta, long precioCompra,
            long stock, long minimo, String foto, boolean activo, Long categoriaId, boolean manejaInventario) {
        this.manejaInventario = manejaInventario;
        this.categoriaId = categoriaId;
        this.id = id;
        this.codigo = codigo == null || codigo.isBlank() ? null : codigo.trim();
        this.nombre = nombre == null ? "" : nombre.trim();
        this.unidad = unidad == null ? Unidad.UNIDAD : unidad;
        this.precioVenta = precioVenta;
        this.precioCompra = precioCompra;
        this.stock = stock;
        this.minimo = minimo;
        this.foto = foto;
        this.activo = activo;
    }

    /** Copia con otro id (se usa cuando la base asigna el id). */
    public Producto conId(long nuevoId) {
        return new Producto(nuevoId, codigo, nombre, unidad, precioVenta, precioCompra, stock, minimo, foto, activo,
                categoriaId, manejaInventario);
    }

    public Producto conActivo(boolean a) {
        return new Producto(id, codigo, nombre, unidad, precioVenta, precioCompra, stock, minimo, foto, a, categoriaId,
                manejaInventario);
    }

    public long getId() {
        return id;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getNombre() {
        return nombre;
    }

    /** Nombre en minusculas y sin tildes, para buscar. */
    public String getClaveNombre() {
        return Geo.normalizar(nombre);
    }

    public Unidad getUnidad() {
        return unidad;
    }

    public long getPrecioVenta() {
        return precioVenta;
    }

    public long getPrecioCompra() {
        return precioCompra;
    }

    public long getStock() {
        return stock;
    }

    public long getMinimo() {
        return minimo;
    }

    public String getFoto() {
        return foto;
    }

    public Long getCategoriaId() {
        return categoriaId;
    }

    public boolean isActivo() {
        return activo;
    }

    public boolean isManejaInventario() {
        return manejaInventario;
    }

    public boolean estaBajoMinimo() {
        return manejaInventario && stock < minimo;
    }

    /** Dos productos son el mismo si tienen el mismo id (aunque uno sea una version editada). */
    @Override
    public boolean equals(Object o) {
        return o instanceof Producto && ((Producto) o).id == id;
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
