package rutas.logica;

/**
 * Lo que se puede ver o hacer. El administrador puede todo. Todo usuario consulta el inventario
 * (precio de venta y existencias); lo demás se le da a cada cajero con una casilla en
 * Configuración › Usuarios (los que dicen «se puede dar»).
 */
public enum Permiso {
    /** Ver la lista de productos: precio de venta y existencias. Todos. */
    VER_INVENTARIO(null),
    /** Crear, editar, borrar e importar productos, cambiar precios y categorías. */
    EDITAR_PRODUCTOS("Editar productos, precios y categorías"),
    /** Registrar y ver compras a proveedores (incluye ver los costos). */
    REGISTRAR_COMPRAS("Registrar compras a proveedores (ve los costos)"),
    /** Registrar, abonar, anular y ver los gastos de la tienda (arriendo, servicios...). */
    REGISTRAR_GASTOS("Registrar y ver gastos de la tienda"),
    /** Ver el valor de compra y la ganancia de los productos. */
    VER_COSTOS("Ver costos y ganancias"),
    /** Usuarios y Configuración: solo el administrador. */
    ADMIN_USUARIOS(null);

    private final String texto;

    Permiso(String texto) {
        this.texto = texto;
    }

    /** Lo que dice la casilla (null si no se le puede dar a un cajero). */
    public String getTexto() {
        return texto;
    }

    public boolean seDa() {
        return texto != null;
    }
}
