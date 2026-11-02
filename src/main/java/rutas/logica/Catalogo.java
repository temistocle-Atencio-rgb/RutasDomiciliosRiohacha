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
            porCodigo.insertar(clave(p.getCodigo()), p);
        }
        porNombre.insertar(claveNombre(p), p);
        porId.insertar(p.getId(), p);
    }

    private void quitar(Producto p) {
        if (p.getCodigo() != null) {
            porCodigo.eliminar(clave(p.getCodigo()));
        }
        porNombre.eliminar(claveNombre(p));
        porId.eliminar(p.getId());
    }

    // ---------------- buscar ----------------

    public Producto buscarCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return null;
        }
        return porCodigo.buscar(clave(codigo)); // lo que lea el lector, en mayusculas
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

    /** El codigo como se guarda y se busca: sin espacios a los lados y en mayusculas. */
    static String clave(String codigo) {
        return codigo.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** El mismo producto con el codigo en mayusculas (abc123 y ABC123 son el mismo codigo). */
    private static Producto conCodigoMayus(Producto p) {
        if (p.getCodigo() == null || p.getCodigo().equals(clave(p.getCodigo()))) {
            return p;
        }
        return new Producto(p.getId(), clave(p.getCodigo()), p.getNombre(), p.getUnidad(), p.getPrecioVenta(),
                p.getPrecioCompra(), p.getStock(), p.getMinimo(), p.getFoto(), p.isActivo(), p.getCategoriaId(),
                p.isManejaInventario());
    }

    /** El mismo producto con el nombre sin espacios dobles. */
    private static Producto conNombreLimpio(Producto p) {
        String n = Textos.limpiar(p.getNombre());
        if (n.equals(p.getNombre())) {
            return p;
        }
        return new Producto(p.getId(), p.getCodigo(), n, p.getUnidad(), p.getPrecioVenta(), p.getPrecioCompra(),
                p.getStock(), p.getMinimo(), p.getFoto(), p.isActivo(), p.getCategoriaId(), p.isManejaInventario());
    }

    public Producto crear(Producto p) throws SQLException {
        p = conNombreLimpio(conCodigoMayus(p));
        validar(p, null);
        Producto guardado = bd.insertarProducto(p.conActivo(true));
        poner(guardado);
        return guardado;
    }

    /** Primero se guarda en la base; solo si salio bien se cambian los arboles. */
    public Producto editar(Producto p) throws SQLException {
        p = conNombreLimpio(conCodigoMayus(p));
        Producto viejo = bd.buscarProductoPorId(p.getId());
        if (viejo == null) {
            throw new IllegalArgumentException("Ese producto ya no existe");
        }
        validar(p, viejo);
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

    /** Codigo: letras, numeros y guion (lista blanca). Sin espacios, comillas, comas ni notacion de Excel. */
    public static final String FORMATO_CODIGO = "[0-9A-Za-z-]{1,40}";

    /**
     * True si el codigo no es comercial (no son 8, 12 o 13 cifras) o si su digito de control
     * (EAN-8, UPC-A, EAN-13) cuadra. Si es false solo se avisa: puede ser un codigo propio.
     */
    public static boolean digitoControlValido(String codigo) {
        if (codigo == null || !codigo.matches("\\d{8}|\\d{12}|\\d{13}")) {
            return true;
        }
        int suma = 0;
        for (int i = codigo.length() - 2, k = 0; i >= 0; i--, k++) {
            suma += (codigo.charAt(i) - '0') * (k % 2 == 0 ? 3 : 1);
        }
        return (10 - suma % 10) % 10 == codigo.charAt(codigo.length() - 1) - '0';
    }

    /** viejo: el producto como esta guardado (null si es nuevo); lo que no cambio no se vuelve a revisar. */
    private void validar(Producto p, Producto viejo) throws SQLException {
        if (p.getNombre().isBlank()) {
            throw new IllegalArgumentException("Escriba el nombre");
        }
        if (viejo == null || !p.getNombre().equals(Textos.limpiar(viejo.getNombre()))) {
            Textos.revisarNombreProducto(p.getNombre()); // un nombre de antes que no se toco se deja como estaba
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
        boolean codigoNuevo = p.getCodigo() != null && (viejo == null || viejo.getCodigo() == null
                || !p.getCodigo().equals(clave(viejo.getCodigo())));
        if (codigoNuevo && !p.getCodigo().matches(FORMATO_CODIGO)) {
            throw new IllegalArgumentException(p.getCodigo().toUpperCase().matches(".*\\d[.,]?\\d*E\\+?\\d+")
                    ? "Ese código parece cambiado por Excel (notación científica); escríbalo completo"
                    : "El código lleva solo letras, números y guion (sin espacios, comillas ni puntos), máximo 40");
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
