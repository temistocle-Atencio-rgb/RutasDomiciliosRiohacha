package rutas.logica;

/** Lo que se puede ver o hacer segun el rol. El cajero no tiene ninguno de estos. */
public enum Permiso {
    VER_COMPRA,
    VER_INVENTARIO,
    ADMIN_USUARIOS,
    EDITAR_PRODUCTOS
}
