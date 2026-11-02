package rutas.logica;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import rutas.estructuras.ArbolAVL;
import rutas.modelo.Geo;
import rutas.modelo.Producto;

/**
 * Productos de la tienda en dos arboles AVL:
 * - por codigo de barras, para el escaner (busqueda exacta en O(log n));
 * - por nombre sin tildes, para buscar por lo que se va escribiendo ("arr" -> arroz).
 * La base de datos es la que guarda; los arboles son para buscar rapido.
 * Solo estan en los arboles los productos activos.
 */
public class Catalogo {

    /** Cuantos resultados se muestran al buscar por nombre. */
    public static final int LIMITE_BUSQUEDA = 30;

    private final BaseTienda bd;
    private ArbolAVL<String, Producto> porCodigo;
    private ArbolAVL<String, Producto> porNombre;
    private ArbolAVL<Long, Producto> porId; // para encontrar rapido el producto de una linea de venta

    public Catalogo(BaseTienda bd) throws SQLException {
        this.bd = bd;
        recargar();
    }

    /** Vuelve a armar los arboles con lo que hay en la base (por ejemplo, si una importacion se deshizo). */
    public final void recargar() throws SQLException {
        porCodigo = new ArbolAVL<>();
        porNombre = new ArbolAVL<>();
        porId = new ArbolAVL<>();
        for (Producto p : bd.listarProductos(false)) {
            poner(p);
        }
    }

    BaseTienda base() {
        return bd;
    }

    /** Clave del arbol por nombre: el id evita que dos productos con el mismo nombre se pisen. */
    private static String claveNombre(Producto p) {
        return p.getClaveNombre() + "#" + p.getId();
    }

    private void poner(Producto p) {
        if (p.getCodigo() != null) {
            porCodigo.insertar(p.getCodigo(), p);
        }
        porNombre.insertar(claveNombre(p), p);
        porId.insertar(p.getId(), p);
    }

    private void quitar(Producto p) {
        if (p.getCodigo() != null) {
            porCodigo.eliminar(p.getCodigo());
        }
        porNombre.eliminar(claveNombre(p));
        porId.eliminar(p.getId());
    }

    // ---------------- buscar ----------------

    public Producto buscarCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return null;
        }
        return porCodigo.buscar(codigo.trim());
    }

    public List<Producto> buscarNombre(String texto, int limite) {
        return porNombre.buscarPorPrefijo(Geo.normalizar(texto), limite);
    }

    /** Lo que usa el buscador y el escaner: si es un codigo exacto, solo ese; si no, por nombre. */
    public List<Producto> buscar(String texto) {
        Producto p = buscarCodigo(texto);
        if (p != null) {
            List<Producto> uno = new ArrayList<>();
            uno.add(p);
            return uno;
        }
        return buscarNombre(texto, LIMITE_BUSQUEDA);
    }

    /** Producto activo por su id (null si no esta o esta desactivado). */
    public Producto buscarPorId(long id) {
        return porId.buscar(id);
    }

    /** Productos de una categoria, en orden por nombre. Con null devuelve todos. */
    public List<Producto> porCategoria(Long categoriaId) {
        if (categoriaId == null) {
            return todos();
        }
        List<Producto> lista = new ArrayList<>();
        for (Producto p : todos()) {
            if (categoriaId.equals(p.getCategoriaId())) {
                lista.add(p);
            }
        }
        return lista;
    }

    /** Vuelve a leer un producto de la base (por ejemplo, despues de vender y bajar su stock). */
    public void recargarProducto(long id) throws SQLException {
        Producto viejo = porId.buscar(id);
        if (viejo != null) {
            quitar(viejo);
        }
        Producto nuevo = bd.buscarProductoPorId(id);
        if (nuevo != null && nuevo.isActivo()) {
            poner(nuevo);
        }
    }

    public List<Producto> todos() {
        return porNombre.enOrden();
    }

    public List<Producto> bajoMinimo() {
        List<Producto> lista = new ArrayList<>();
        for (Producto p : todos()) {
            if (p.estaBajoMinimo()) {
                lista.add(p);
            }
        }
        return lista;
    }

    public int tamano() {
        return porNombre.getTamano();
    }

    public boolean balanceado() {
        return porNombre.estaBalanceado() && porCodigo.estaBalanceado() && porId.estaBalanceado();
    }

    // ---------------- cambiar ----------------

    public Producto crear(Producto p) throws SQLException {
        validar(p);
        Producto guardado = bd.insertarProducto(p.conActivo(true));
        poner(guardado);
        return guardado;
    }

    /** Primero se guarda en la base; solo si salio bien se cambian los arboles. */
    public Producto editar(Producto p) throws SQLException {
        validar(p);
        Producto viejo = bd.buscarProductoPorId(p.getId());
        if (viejo == null) {
            throw new IllegalArgumentException("Ese producto ya no existe");
        }
        Producto nuevo = p.conActivo(viejo.isActivo());
        bd.actualizarProducto(nuevo);
        if (viejo.isActivo()) {
            quitar(viejo);
            poner(nuevo);
        }
        return nuevo;
    }

    public void desactivar(long id) throws SQLException {
        Producto p = bd.buscarProductoPorId(id);
        if (p == null || !p.isActivo()) {
            return;
        }
        bd.cambiarActivoProducto(id, false);
        quitar(p);
    }

    public Producto reactivar(long id) throws SQLException {
        Producto p = bd.buscarProductoPorId(id);
        if (p == null) {
            throw new IllegalArgumentException("Ese producto ya no existe");
        }
        if (!p.isActivo()) {
            bd.cambiarActivoProducto(id, true);
            p = p.conActivo(true);
            poner(p);
        }
        return p;
    }

    /** Todos los productos, tambien los desactivados (para la pantalla de productos). */
    public List<Producto> todosConInactivos() throws SQLException {
        return bd.listarProductos(true);
    }

    private void validar(Producto p) throws SQLException {
        if (p.getNombre().isBlank()) {
            throw new IllegalArgumentException("Escriba el nombre");
        }
        if (p.getPrecioVenta() < 0 || p.getPrecioCompra() < 0) {
            throw new IllegalArgumentException("El precio no puede ser negativo");
        }
        if (p.getPrecioVenta() == 0) {
            throw new IllegalArgumentException("El precio de venta debe ser mayor que $0");
        }
        if (p.getPrecioVenta() > ImportadorProductos.PRECIO_MAXIMO || p.getPrecioCompra() > ImportadorProductos.PRECIO_MAXIMO) {
            throw new IllegalArgumentException("El valor máximo es $99.999.999");
        }
        if (p.getCodigo() != null) {
            Producto otro = bd.buscarPorCodigoEnBase(p.getCodigo());
            if (otro != null && otro.getId() != p.getId()) {
                throw new IllegalArgumentException(otro.isActivo()
                        ? "Ese código ya es de «" + otro.getNombre() + "»"
                        : "Ese código es de «" + otro.getNombre() + "», que está desactivado");
            }
        }
    }
}
