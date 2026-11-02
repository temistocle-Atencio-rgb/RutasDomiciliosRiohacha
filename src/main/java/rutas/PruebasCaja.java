package rutas;

import static rutas.Pruebas.revisar;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import rutas.logica.BaseTienda;
import rutas.logica.Carrito;
import rutas.logica.Cobro;
import rutas.logica.Granel;
import rutas.logica.Catalogo;
import rutas.logica.ImportadorProductos;
import rutas.logica.Ingreso;
import rutas.logica.Permiso;
import rutas.logica.Seguridad;
import rutas.logica.Sesion;
import rutas.modelo.Rol;
import rutas.modelo.Usuario;
import rutas.modelo.Categoria;
import rutas.modelo.MetodoPago;
import rutas.modelo.Pago;
import rutas.modelo.LineaVenta;
import rutas.modelo.Producto;
import rutas.modelo.Unidad;

/**
 * Pruebas de la caja de la tienda (usuarios, productos, importar lista).
 * Son rapidas porque no cargan el mapa. Tambien corren dentro de Pruebas.
 */
public class PruebasCaja {

    public static void main(String[] args) throws Exception {
        correr();
        Pruebas.resumen();
    }

    static void correr() throws Exception {
        probarSeguridad();
        probarModelos();
        probarUsuarios();
        probarCatalogo();
        probarImportador();
        probarCarrito();
        probarCobro();
        probarVentas();
        probarImportarCategorias();
        probarListado();
        probarArchivos();
        probarExistencias();
        probarPorcentajeYNota();
        probarCodigoBarras();
        probarFichaProducto();
        probarAjustesRecibo();
        probarCategoriasExcel();
        probarCompras();
        probarHistorialCompras();
        probarMediosPago();
        probarClientesCaja();
        probarCorreo();
        probarDomicilioCaja();
        probarReciboCarta();
        String wa = rutas.logica.Avisos.enlaceWhatsAppSinNumero("Ruta:\n1. Calle 15 # 7-20 & más");
        revisar("whatsapp: enlace directo a la app (sin navegador)",
                rutas.logica.Avisos.enlaceWhatsAppApp("", "Hola mundo").equals("whatsapp://send?text=Hola%20mundo")
                && rutas.logica.Avisos.enlaceWhatsAppApp("312 777 8899", "a").equals("whatsapp://send?phone=573127778899&text=a"));
        revisar("whatsapp: distingue la app de un navegador en el registro",
                rutas.vista.AbrirWhatsApp.esLaApp("AppXkfx1n2v3xk7b8s4t6e7j1gbn5xcjb0e3")
                && rutas.vista.AbrirWhatsApp.esLaApp("\"C:\\Users\\u\\AppData\\Local\\WhatsApp\\WhatsApp.exe\" \"%1\"")
                && !rutas.vista.AbrirWhatsApp.esLaApp("MSEdgeHTM")
                && !rutas.vista.AbrirWhatsApp.esLaApp("\"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe\" --single-argument %1")
                && !rutas.vista.AbrirWhatsApp.esLaApp("ChromeHTML"));
        revisar("whatsapp: enlace sin número con la ruta escrita", wa.startsWith("https://wa.me/?text=")
                && wa.contains("%0A") && wa.contains("%26") && !wa.contains(" "));
    }

    /** El PDF del correo: hoja carta, valor en letras y varias hojas si son muchos productos. */
    private static void probarReciboCarta() throws Exception {
        System.out.println("--- Recibo carta ---");
        revisar("letras: 12.500", rutas.logica.Letras.pesos(12500).equals("Doce mil quinientos pesos m/cte"));
        revisar("letras: 21.000 (veintiún mil)", rutas.logica.Letras.pesos(21000).equals("Veintiún mil pesos m/cte"));
        revisar("letras: 1.747.500", rutas.logica.Letras.pesos(1747500)
                .equals("Un millón setecientos cuarenta y siete mil quinientos pesos m/cte"));
        revisar("letras: 2.000.000 (de pesos)", rutas.logica.Letras.pesos(2000000).equals("Dos millones de pesos m/cte"));
        revisar("letras: 101 y 100", rutas.logica.Letras.pesos(101).equals("Ciento un pesos m/cte")
                && rutas.logica.Letras.pesos(100).equals("Cien pesos m/cte"));
        Object[] uno = rutas.vista.PruebaAcceso.reciboCarta(3);
        Object[] cien = rutas.vista.PruebaAcceso.reciboCarta(100);
        String pdf1 = new String((byte[]) uno[1], java.nio.charset.StandardCharsets.ISO_8859_1);
        String pdf100 = new String((byte[]) cien[1], java.nio.charset.StandardCharsets.ISO_8859_1);
        revisar("recibo carta: 3 productos caben en una hoja carta", (int) uno[0] == 1 && pdf1.contains("/Count 1")
                && pdf1.contains("/MediaBox [0 0 612.00 792.00]") && pdf1.trim().endsWith("%%EOF"));
        revisar("recibo carta: 100 productos siguen en varias hojas", (int) cien[0] >= 3
                && pdf100.contains("/Count " + cien[0]));
    }

    /** Venta a domicilio: lo que se guarda en la entrega y lo que sale en el recibo para el repartidor. */
    private static void probarDomicilioCaja() {
        System.out.println("--- Venta a domicilio ---");
        rutas.modelo.Parada p = new rutas.modelo.Parada("Marcela Luna", "Calle 15 # 7-20", 11.54, -72.9);
        p.setValorPedido(12500);
        p.setPagaCon(50000);
        rutas.modelo.Parada q = new rutas.modelo.Parada("Marcela Luna", "Calle 15 # 7-20", 11.54, -72.9);
        q.copiarDatosDe(p);
        revisar("al editar la entrega no se pierde con cuánto paga", q.getPagaCon() == 50000 && q.getValorPedido() == 12500);
        p.setPagaCon(-5);
        revisar("paga con no queda negativo", p.getPagaCon() == 0);
        rutas.logica.Cuadre c = new rutas.logica.Cuadre();
        c.agregar(12500, 3000, false); // contra entrega
        c.agregar(0, 3000, false);     // pagó en la tienda: solo el domicilio
        revisar("cuadre: el repartidor entrega pedido contra entrega + los dos domicilios", c.getEfectivo() == 18500
                && c.getDomicilios() == 6000 && c.getPedidos() == 12500);
        int sin = rutas.vista.PruebaAcceso.altoRecibo(null);
        int con = rutas.vista.PruebaAcceso.altoRecibo(java.util.List.of("*Marcela Luna", "Calle 15 # 7-20",
                "Domicilio: $3.000", "*CONTRA ENTREGA: cobrar $15.500"));
        revisar("el recibo a domicilio es más largo (lleva los datos del repartidor)", con > sin + 40);
    }

    private static void probarSeguridad() {
        System.out.println("--- Contraseñas (PBKDF2) ---");
        byte[] sal = Seguridad.crearSal();
        byte[] h1 = Seguridad.hash("clave123".toCharArray(), sal, 1000);
        byte[] h2 = Seguridad.hash("clave123".toCharArray(), sal, 1000);
        revisar("la misma clave con la misma sal da el mismo hash", Arrays.equals(h1, h2));
        byte[] h3 = Seguridad.hash("clave123".toCharArray(), Seguridad.crearSal(), 1000);
        revisar("con otra sal el hash cambia", !Arrays.equals(h1, h3));
        revisar("una clave mala no coincide", !Seguridad.coincide("clave124".toCharArray(), sal, 1000, h1));
        revisar("la clave buena coincide", Seguridad.coincide("clave123".toCharArray(), sal, 1000, h1));
        revisar("sal de 16 bytes y hash de 32", sal.length == 16 && h1.length == 32);
        revisar("se guarda y se lee en texto", Arrays.equals(Seguridad.deTexto(Seguridad.aTexto(h1)), h1));
        revisar("se usan 210000 vueltas", Seguridad.ITERACIONES == 210000);
    }

    private static void probarModelos() {
        System.out.println("--- Producto ---");
        Producto p = new Producto(0, "  ", "Arróz Diana", Unidad.UNIDAD, 2500, 2000, 500, 1000, null, true);
        revisar("codigo en blanco queda vacio (null)", p.getCodigo() == null);
        revisar("nombre normalizado sin tilde", p.getClaveNombre().equals("arroz diana"));
        revisar("stock 500 bajo minimo 1000", p.estaBajoMinimo());
        revisar("texto corto de la libra", Unidad.LIBRA.getCorto().equals("lb"));
    }

    /** Base de la tienda en una carpeta temporal, con pocas vueltas para que sea rapida. */
    static BaseTienda baseTemporal() throws Exception {
        File carpeta = Files.createTempDirectory("domiruta_tienda").toFile();
        return new BaseTienda(new File(carpeta, "tienda.db"), 1000);
    }

    private static boolean lanza(Class<? extends Throwable> tipo, String mensaje, Runnable r) {
        try {
            r.run();
            return false;
        } catch (Throwable t) {
            return tipo.isInstance(t) && (mensaje == null || mensaje.equals(t.getMessage()));
        }
    }

    /** Como lanza, pero basta con que el mensaje contenga el pedazo dado. */
    private static boolean lanzaCon(Class<? extends Throwable> tipo, String pedazo, Runnable r) {
        try {
            r.run();
            return false;
        } catch (Throwable t) {
            Throwable c = t instanceof RuntimeException && t.getCause() != null && !tipo.isInstance(t) ? t.getCause() : t;
            return tipo.isInstance(c) && c.getMessage() != null && c.getMessage().contains(pedazo);
        }
    }

    private static String mensajeIngreso(Ingreso in, String u, String c) {
        try {
            in.entrar(u, c.toCharArray());
            return "ENTRO";
        } catch (Ingreso.IngresoException e) {
            return e.getMessage();
        }
    }

    private static void probarUsuarios() throws Exception {
        System.out.println("--- Usuarios e ingreso ---");
        try (BaseTienda bd = baseTemporal()) {
            revisar("base nueva sin usuarios", bd.contarUsuarios() == 0);
            Usuario ana = bd.crearUsuario(" Ana ", "Ana Gómez", Rol.ADMIN, "secreto1".toCharArray());
            revisar("usuario guardado en minusculas y sin espacios", ana.getUsuario().equals("ana"));
            revisar("usuario repetido (con mayusculas) se rechaza", lanza(IllegalArgumentException.class,
                    "Ese usuario ya existe", () -> crear(bd, "ANA", Rol.CAJERO, "otraclave")));
            revisar("contraseña corta se rechaza", lanza(IllegalArgumentException.class,
                    "La contraseña debe tener al menos 6 caracteres", () -> crear(bd, "pepe", Rol.CAJERO, "123")));

            AtomicLong reloj = new AtomicLong(1_000_000);
            Ingreso in = new Ingreso(bd, reloj::get);
            Sesion s = in.entrar("ANA", "secreto1".toCharArray());
            revisar("entra la admin escribiendo el usuario en mayusculas", s.esAdmin());
            revisar("clave mala da el mensaje unico",
                    mensajeIngreso(in, "ana", "mala").equals("Usuario o contraseña incorrectos"));
            revisar("usuario que no existe da el mismo mensaje",
                    mensajeIngreso(in, "nadie", "secreto1").equals("Usuario o contraseña incorrectos"));
            for (int i = 0; i < 3; i++) {
                mensajeIngreso(in, "ana", "mala");
            }
            revisar("tras 5 fallos ni la clave buena entra",
                    mensajeIngreso(in, "ana", "secreto1").startsWith("Demasiados intentos. Espere "));
            reloj.addAndGet(30_001);
            revisar("pasados 30 segundos ya entra", mensajeIngreso(in, "ana", "secreto1").equals("ENTRO"));
            revisar("un ingreso bueno reinicia el conteo",
                    mensajeIngreso(in, "ana", "mala").equals("Usuario o contraseña incorrectos"));

            Usuario luis = bd.crearUsuario("luis", "Luis", Rol.CAJERO, "clavel".toCharArray());
            Sesion cajero = new Ingreso(bd, reloj::get).entrar("luis", "clavel".toCharArray());
            revisar("el cajero no ve precio de compra", !cajero.puede(Permiso.VER_COSTOS) && !cajero.esAdmin());
            revisar("la admin si", s.puede(Permiso.VER_COSTOS) && s.puede(Permiso.ADMIN_USUARIOS));
            revisar("el cajero consulta el inventario pero no edita, ni compra, ni entra a usuarios",
                    cajero.puede(Permiso.VER_INVENTARIO) && !cajero.puede(Permiso.EDITAR_PRODUCTOS)
                    && !cajero.puede(Permiso.REGISTRAR_COMPRAS) && !cajero.puede(Permiso.ADMIN_USUARIOS));
            bd.guardarPermisos(luis.getId(), java.util.EnumSet.of(Permiso.EDITAR_PRODUCTOS, Permiso.REGISTRAR_COMPRAS,
                    Permiso.ADMIN_USUARIOS)); // usuarios no se puede dar: no se guarda
            Sesion conPermisos = new Ingreso(bd, reloj::get).entrar("luis", "clavel".toCharArray());
            revisar("permisos por usuario: edita productos y registra compras (y por eso ve costos)",
                    conPermisos.puede(Permiso.EDITAR_PRODUCTOS) && conPermisos.puede(Permiso.REGISTRAR_COMPRAS)
                    && conPermisos.puede(Permiso.VER_COSTOS) && !conPermisos.puede(Permiso.ADMIN_USUARIOS)
                    && !conPermisos.esAdmin());
            bd.guardarPermisos(luis.getId(), java.util.EnumSet.of(Permiso.VER_COSTOS));
            Sesion soloCostos = new Ingreso(bd, reloj::get).entrar("luis", "clavel".toCharArray());
            revisar("permisos: se pueden quitar (solo ve costos)", soloCostos.puede(Permiso.VER_COSTOS)
                    && !soloCostos.puede(Permiso.EDITAR_PRODUCTOS) && !soloCostos.puede(Permiso.REGISTRAR_COMPRAS));
            bd.guardarPermisos(luis.getId(), java.util.EnumSet.noneOf(Permiso.class));
            char[] borrada = "secreto1".toCharArray();
            new Ingreso(bd, reloj::get).entrar("ana", borrada);
            revisar("la contraseña se borra de memoria despues de usarla", new String(borrada).equals("\0\0\0\0\0\0\0\0"));

            bd.cambiarClave(luis.getId(), "nuevaclave".toCharArray());
            revisar("cambiar contraseña", bd.validar("luis", "nuevaclave".toCharArray()) != null
                    && bd.validar("luis", "clavel".toCharArray()) == null);
            bd.cambiarActivo(luis.getId(), false);
            revisar("cajero desactivado no entra",
                    mensajeIngreso(new Ingreso(bd, reloj::get), "luis", "nuevaclave").equals("Usuario o contraseña incorrectos"));
            revisar("no se puede desactivar a la unica admin", lanza(IllegalStateException.class,
                    "Debe quedar al menos un administrador activo", () -> activar(bd, ana.getId(), false)));
            revisar("lista de usuarios", bd.listarUsuarios().size() == 2);

            try (BaseTienda otra = new BaseTienda(bd.getArchivo(), 1000)) {
                revisar("el programa abierto dos veces puede leer", otra.listarUsuarios().size() == 2
                        && bd.listarUsuarios().size() == 2);
            }
        }
    }

    private static void crear(BaseTienda bd, String u, Rol r, String c) {
        try {
            bd.crearUsuario(u, u, r, c.toCharArray());
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void activar(BaseTienda bd, long id, boolean a) {
        try {
            bd.cambiarActivo(id, a);
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Producto prod(String codigo, String nombre, Unidad u, long precio) {
        return new Producto(0, codigo, nombre, u, precio, precio * 8 / 10, 5000, 1000, null, true);
    }

    private static void probarCatalogo() throws Exception {
        System.out.println("--- Catalogo de productos (AVL) ---");
        try (BaseTienda bd = baseTemporal()) {
            Catalogo cat = new Catalogo(bd);
            Producto arroz = cat.crear(prod("7702511000014", "Arroz Diana 500g", Unidad.UNIDAD, 2500));
            Producto arepas = cat.crear(prod(null, "Arepas Doña Paisa", Unidad.UNIDAD, 3000));
            Producto azucar = cat.crear(prod(null, "Azúcar", Unidad.KILO, 4400));
            cat.crear(prod("7702511000021", "Arroz Diana 500g", Unidad.UNIDAD, 2600));
            revisar("los productos reciben id", arroz.getId() > 0 && arepas.getId() != arroz.getId());

            revisar("buscar por codigo (con espacios del escaner)",
                    cat.buscarCodigo(" 7702511000014 ").getId() == arroz.getId());
            revisar("'arr' trae los dos arroces y no la azucar", cat.buscarNombre("arr", 0).size() == 2);
            revisar("'ar' trae arroces y arepas", cat.buscarNombre("ar", 0).size() == 3);
            revisar("'AZU' encuentra la azucar sin tilde ni mayuscula",
                    cat.buscarNombre("AZU", 0).size() == 1 && cat.buscarNombre("AZU", 0).get(0).getId() == azucar.getId());
            revisar("buscar con codigo exacto devuelve solo ese", cat.buscar("7702511000014").size() == 1);
            revisar("buscar con nombre trae la lista", cat.buscar("arroz").size() == 2);

            cat.crear(prod("0705632441947", "Galletas", Unidad.UNIDAD, 1200));
            revisar("codigo con cero adelante se conserva", cat.buscarCodigo("0705632441947") != null);
            revisar("codigo repetido se rechaza con el nombre del dueño", lanza(IllegalArgumentException.class,
                    "Ese código ya es de «Arroz Diana 500g»", () -> crearEn(cat, prod("7702511000014", "Otro", Unidad.UNIDAD, 1))));
            revisar("nombre vacio se rechaza", lanza(IllegalArgumentException.class,
                    "Escriba el nombre", () -> crearEn(cat, prod(null, "  ", Unidad.UNIDAD, 1))));
            revisar("precio negativo se rechaza", lanza(IllegalArgumentException.class,
                    "El precio no puede ser negativo", () -> crearEn(cat, prod(null, "Malo", Unidad.UNIDAD, -5))));

            cat.editar(new Producto(arepas.getId(), null, "Arepa de choclo", Unidad.UNIDAD, 3200, 2500, 1000, 0, null, true));
            revisar("al renombrar el nombre viejo ya no aparece", cat.buscarNombre("arepas", 0).isEmpty()
                    && cat.buscarNombre("arepa de", 0).size() == 1);
            cat.editar(new Producto(arroz.getId(), "7700000000001", arroz.getNombre(), Unidad.UNIDAD, 2500, 2000, 5000, 1000, null, true));
            revisar("al cambiar el codigo el viejo ya no se encuentra", cat.buscarCodigo("7702511000014") == null
                    && cat.buscarCodigo("7700000000001").getId() == arroz.getId());

            cat.desactivar(azucar.getId());
            revisar("producto desactivado no sale en la busqueda", cat.buscarNombre("az", 0).isEmpty());
            cat.reactivar(azucar.getId());
            revisar("reactivado vuelve a salir", cat.buscarNombre("az", 0).size() == 1);

            Producto gaseosa = cat.crear(prod("7702090000016", "Gaseosa 400ml", Unidad.UNIDAD, 2000));
            cat.desactivar(gaseosa.getId());
            revisar("codigo de un producto desactivado da su aviso", lanza(IllegalArgumentException.class,
                    "Ese código es de «Gaseosa 400ml», que está desactivado",
                    () -> crearEn(cat, prod("7702090000016", "Nueva", Unidad.UNIDAD, 1))));
            revisar("si la base rechaza el cambio, los arboles quedan igual", lanza(IllegalArgumentException.class, null,
                    () -> editarEn(cat, new Producto(arroz.getId(), "7702090000016", "Arroz", Unidad.UNIDAD, 1, 1, 1, 1, null, true)))
                    && cat.buscarCodigo("7700000000001") != null);
            revisar("bajo el minimo", cat.bajoMinimo().size() == 0);

            Catalogo otra = new Catalogo(bd);
            revisar("recargar desde la base da los mismos productos: " + otra.tamano(),
                    otra.tamano() == cat.tamano() && otra.balanceado());
            revisar("todos en orden por nombre", otra.todos().get(0).getNombre().startsWith("Arepa"));

            for (int i = 0; i < 1000; i++) {
                cat.crear(prod(null, String.format("Producto %04d", i), Unidad.UNIDAD, 100));
            }
            revisar("1000 productos en orden alfabetico y los arboles siguen balanceados", cat.balanceado());
        }
    }

    private static void crearEn(Catalogo c, Producto p) {
        try {
            c.crear(p);
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void editarEn(Catalogo c, Producto p) {
        try {
            c.editar(p);
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean pesos(String texto, long esperado) {
        return ImportadorProductos.leerPesos(texto) == esperado;
    }

    private static void probarImportador() throws Exception {
        System.out.println("--- Importar lista de productos ---");
        revisar("precios a la colombiana", pesos("$2.500", 2500) && pesos("2.500,00", 2500)
                && pesos("2,500", 2500) && pesos("2500", 2500) && pesos(" $ 12.000 ", 12000));
        revisar("precio con centavos se rechaza", lanza(IllegalArgumentException.class,
                "Los precios van sin centavos", () -> ImportadorProductos.leerPesos("2.500,50")));
        revisar("cantidades en milesimas", ImportadorProductos.leerCantidad("1,5") == 1500
                && ImportadorProductos.leerCantidad("1.5") == 1500 && ImportadorProductos.leerCantidad("3") == 3000);
        revisar("punto con 3 cifras es de miles: 1.200 bolsas son 1200",
                ImportadorProductos.leerCantidad("1.200") == 1_200_000
                && ImportadorProductos.leerCantidad("1.200,5") == 1_200_500);

        File carpeta = Files.createTempDirectory("domiruta_csv").toFile();
        File csv1 = new File(carpeta, "lista.csv");
        String texto1 = "Nombre;Código;Unidad;Precio_venta;Precio_compra;Stock;Minimo\r\n"
                + "Arroz Diana;7702511000014;und;$2.500;$2.000;24;6\r\n"
                + "Azúcar;;kilo;4.400;3.600;10,5;2\r\n"
                + "\"Queso costeño; libra\";;LIBRA;12.000;9.000;3;1\r\n"
                + ";7702000000000;und;1000;800;1;1\r\n"
                + "Gaseosa;7,70E+12;und;2000;1500;12;6\r\n";
        Files.write(csv1.toPath(), texto1.getBytes("windows-1252"));
        try (BaseTienda bd = baseTemporal()) {
            Catalogo cat = new Catalogo(bd);
            ImportadorProductos.Resultado r = ImportadorProductos.importar(csv1, cat);
            revisar("3 creados y 2 con error: " + r.creados + " / " + r.errores, r.creados == 3 && r.errores.size() == 2);
            revisar("la fila sin nombre se nombra", r.errores.size() == 2 && r.errores.get(0).startsWith("Fila 5: "));
            revisar("la fila con codigo de Excel explica el problema", r.errores.size() == 2
                    && r.errores.get(1).equals("Fila 6: Excel cambió el código; formatee la columna como texto"));
            revisar("tilde bien leida del archivo de Excel", cat.buscarNombre("azucar", 0).size() == 1
                    && cat.buscarNombre("azucar", 0).get(0).getNombre().equals("Azúcar"));
            Producto az = cat.buscarNombre("azucar", 0).get(0);
            revisar("unidad, precio y stock leidos", az.getUnidad() == Unidad.KILO && az.getPrecioVenta() == 4400
                    && az.getStock() == 10500 && az.getMinimo() == 2000);
            revisar("campo entre comillas con punto y coma", cat.buscarNombre("queso", 0).size() == 1
                    && cat.buscarNombre("queso", 0).get(0).getUnidad() == Unidad.LIBRA);

            File csv2 = new File(carpeta, "lista2.csv");
            Files.write(csv2.toPath(), ("\uFEFFnombre,codigo,precio_venta\n"
                    + "Arroz Diana 500g,7702511000014,2700\n").getBytes("UTF-8"));
            ImportadorProductos.Resultado r2 = ImportadorProductos.importar(csv2, cat);
            Producto arroz = cat.buscarCodigo("7702511000014");
            revisar("mismo codigo actualiza el producto", r2.actualizados == 1 && r2.creados == 0
                    && arroz.getPrecioVenta() == 2700 && arroz.getNombre().equals("Arroz Diana 500g"));
            revisar("lo que no venia en el archivo se conserva", arroz.getPrecioCompra() == 2000 && arroz.getStock() == 24000);

            File csv3 = new File(carpeta, "malo.csv");
            Files.write(csv3.toPath(), "producto;valor\nArroz;100\n".getBytes("UTF-8"));
            revisar("archivo sin columnas obligatorias se rechaza", lanza(IllegalArgumentException.class,
                    "Faltan columnas: nombre, precio_venta", () -> importarEn(csv3, cat)));
        }
    }

    private static void importarEn(File f, Catalogo c) {
        try {
            ImportadorProductos.importar(f, c);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Producto conId(long id, String nombre, Unidad u, long precio) {
        return new Producto(id, null, nombre, u, precio, precio * 8 / 10, 10_000, 1000, null, true);
    }

    private static void probarCarrito() {
        rutas.logica.Carrito c0 = new rutas.logica.Carrito();
        c0.agregar(new Producto(1, null, "Pan", Unidad.UNIDAD, 500, 300, 10, 0, null, true));
        revisar("precio editado a 0 se rechaza", lanza(IllegalArgumentException.class, "El precio debe ser mayor que cero",
                () -> c0.cambiarPrecio(0, 0)));

        System.out.println("--- Carrito de la venta ---");
        Producto arroz = conId(1, "Arroz", Unidad.UNIDAD, 2500);
        Producto azucar = conId(2, "Azúcar", Unidad.KILO, 4400);
        Carrito c = new Carrito();
        revisar("carrito nuevo vacio", c.estaVacio() && c.getTotal() == 0);
        c.agregar(arroz);
        c.agregar(arroz);
        revisar("el mismo producto suma en la misma linea", c.getLineas().size() == 1
                && c.getLineas().get(0).getCantidad() == 2000 && c.getTotal() == 5000);
        revisar("granel: $2.000 de azucar a $4.400 el kilo son 455 g", Granel.cantidadPorValor(2000, 4400) == 455);
        c.agregarPorValor(azucar, 2000);
        revisar("granel por valor: el subtotal es lo que pidio el cliente ($2.000)",
                c.getLineas().get(1).getSubtotal() == 2000 && c.getTotal() == 7000);
        c.agregar(azucar, 1500);
        revisar("granel por peso: 1,5 kg a $4.400 = $6.600", c.getLineas().size() == 3
                && c.getLineas().get(2).getSubtotal() == 6600);
        c.cambiarPrecio(0, 2400);
        revisar("cambiar el precio en la venta no cambia el producto", c.getLineas().get(0).getSubtotal() == 4800
                && arroz.getPrecioVenta() == 2500);
        c.cambiarDescuento(0, 800);
        revisar("descuento en pesos", c.getLineas().get(0).getSubtotal() == 4000);
        revisar("descuento mayor que la linea se rechaza", lanza(IllegalArgumentException.class,
                "El descuento no puede ser mayor que el valor", () -> c.cambiarDescuento(0, 9000)));
        revisar("cantidad en cero se rechaza", lanza(IllegalArgumentException.class,
                "La cantidad debe ser mayor que cero", () -> c.cambiarCantidad(0, 0)));
        revisar("precio negativo se rechaza", lanza(IllegalArgumentException.class,
                "El precio debe ser mayor que cero", () -> c.cambiarPrecio(0, -1)));
        c.cambiarCantidad(1, 1000);
        revisar("cambiar la cantidad de una linea por valor quita el valor fijo", c.getLineas().get(1).getSubtotal() == 4400);
        c.quitar(2);
        revisar("quitar linea", c.getLineas().size() == 2 && c.cantidadProductos() == 2);
        c.vaciar();
        revisar("vaciar", c.estaVacio());
    }

    private static Cobro.Resultado cobro(long total, Object... pagos) {
        java.util.List<Pago> l = new java.util.ArrayList<>();
        for (int i = 0; i < pagos.length; i += 2) {
            l.add(new Pago((MetodoPago) pagos[i], (Long) pagos[i + 1]));
        }
        return Cobro.calcular(total, l);
    }

    private static void probarCobro() {
        rutas.logica.Cobro.Resultado cod = rutas.logica.Cobro.calcular(15_100,
                java.util.List.of(new rutas.modelo.Pago(rutas.modelo.MetodoPago.EFECTIVO, 7_700_000_100_021L)));
        revisar("un codigo escaneado en el efectivo no cuenta como pago", !cod.isAlcanza() && cod.getError() != null);
        rutas.logica.Cobro.Resultado billete = rutas.logica.Cobro.calcular(2_000,
                java.util.List.of(new rutas.modelo.Pago(rutas.modelo.MetodoPago.EFECTIVO, 100_000)));
        revisar("pagar $2.000 con un billete de $100.000 si se puede", billete.isAlcanza() && billete.getVueltas() == 98_000);

        System.out.println("--- Cobro ---");
        Cobro.Resultado r = cobro(18000, MetodoPago.EFECTIVO, 18000L);
        revisar("efectivo exacto", r.isAlcanza() && r.getVueltas() == 0);
        r = cobro(16300, MetodoPago.EFECTIVO, 20000L);
        revisar("con vueltas de $3.700", r.isAlcanza() && r.getVueltas() == 3700 && r.getRecibidoEfectivo() == 20000);
        r = cobro(16300, MetodoPago.EFECTIVO, 15000L);
        revisar("no alcanza: faltan $1.300", !r.isAlcanza() && r.getFaltan() == 1300);
        r = cobro(25000, MetodoPago.NEQUI, 10000L, MetodoPago.EFECTIVO, 20000L);
        revisar("combinado Nequi + efectivo: vueltas $5.000", r.isAlcanza() && r.getVueltas() == 5000);
        r = cobro(25000, MetodoPago.NEQUI, 20000L, MetodoPago.DAVIPLATA, 10000L);
        revisar("sin efectivo no puede pasar del total", !r.isAlcanza()
                && "El pago pasa del total".equals(r.getError()));
        r = cobro(5000, MetodoPago.NEQUI, 2000L, MetodoPago.EFECTIVO, 1000L);
        revisar("las vueltas nunca superan el efectivo", !r.isAlcanza() && r.getFaltan() == 2000);
        revisar("pago negativo se rechaza", lanza(IllegalArgumentException.class, null,
                () -> new Pago(MetodoPago.EFECTIVO, -5)));
    }

    private static java.util.List<Pago> pagos(Object... p) {
        java.util.List<Pago> l = new java.util.ArrayList<>();
        for (int i = 0; i < p.length; i += 2) {
            l.add(new Pago((MetodoPago) p[i], (Long) p[i + 1]));
        }
        return l;
    }

    private static void probarVentas() throws Exception {
        System.out.println("--- Ventas, espera y categorias ---");
        try (BaseTienda bd = baseTemporal()) {
            long cajero = bd.crearUsuario("caja", "Caja", Rol.CAJERO, "clave123".toCharArray()).getId();
            Categoria granos = bd.crearCategoria("Granos", null, 1);
            Categoria dulces = bd.crearCategoria("Dulces", null, 2);
            revisar("categorias en su orden", bd.listarCategorias(false).size() == 2
                    && bd.listarCategorias(false).get(0).getNombre().equals("Granos"));
            revisar("categoria sin nombre se rechaza", lanza(IllegalArgumentException.class, "Escriba el nombre",
                    () -> { try { bd.crearCategoria(" ", null, 0); } catch (java.sql.SQLException e) { throw new RuntimeException(e); } }));
            Catalogo cat = new Catalogo(bd);
            Producto arroz = cat.crear(new Producto(0, "7702511000014", "Arroz", Unidad.UNIDAD, 2500, 2000, 3000, 0,
                    null, true, granos.getId()));
            Producto azucar = cat.crear(new Producto(0, null, "Azúcar", Unidad.KILO, 4400, 3600, 10_000, 0,
                    null, true, granos.getId()));
            Producto chicle = cat.crear(new Producto(0, null, "Chicle", Unidad.UNIDAD, 200, 100, 1000, 0,
                    null, true, dulces.getId()));
            revisar("productos por categoria", cat.porCategoria(granos.getId()).size() == 2
                    && cat.porCategoria(null).size() == 3);
            revisar("la categoria se guarda en la base", new Catalogo(bd).buscarCodigo("7702511000014").getCategoriaId()
                    == granos.getId());

            Carrito c = new Carrito();
            c.agregar(arroz);
            c.agregar(arroz);
            c.agregarPorValor(azucar, 2000);
            long idEspera = bd.guardarEspera(null, "Marcela", cajero, c);
            revisar("venta en espera listada con su nombre y total", bd.listarEspera().size() == 1
                    && bd.listarEspera().get(0).getNombre().equals("Marcela") && bd.listarEspera().get(0).getTotal() == 7000);
            revisar("la espera no baja el stock", bd.buscarProductoPorId(arroz.getId()).getStock() == 3000);
            BaseTienda.Retomada r = bd.cargarEspera(idEspera, cat);
            revisar("la espera se recupera igual (por valor incluido)", r.getCarrito().getTotal() == 7000
                    && r.getCarrito().getLineas().get(1).esPorValor() && r.getAvisos().isEmpty());

            Carrito c2 = new Carrito();
            c2.agregar(chicle);
            Cobro.Resultado res2 = Cobro.calcular(c2.getTotal(), pagos(MetodoPago.EFECTIVO, 1000L));
            long n1 = bd.cobrar(null, cajero, null, c2, pagos(MetodoPago.EFECTIVO, 1000L), res2);
            cat.recargarProducto(chicle.getId());
            revisar("vender el ultimo chicle deja el stock en 0", bd.buscarProductoPorId(chicle.getId()).getStock() == 0
                    && cat.buscarPorId(chicle.getId()).getStock() == 0);

            Carrito c1 = r.getCarrito();
            Cobro.Resultado res = Cobro.calcular(c1.getTotal(), pagos(MetodoPago.NEQUI, 2000L, MetodoPago.EFECTIVO, 10000L));
            long n2 = bd.cobrar(idEspera, cajero, "Marcela", c1, pagos(MetodoPago.NEQUI, 2000L, MetodoPago.EFECTIVO, 10000L), res);
            revisar("numeros de venta consecutivos aunque la espera se creo antes: " + n1 + ", " + n2, n1 == 1 && n2 == 2);
            revisar("cobrar baja el stock (2 arroces y 455 g de azucar)", bd.buscarProductoPorId(arroz.getId()).getStock() == 1000
                    && bd.buscarProductoPorId(azucar.getId()).getStock() == 10_000 - 455);
            revisar("la venta cobrada sale de la espera", bd.listarEspera().isEmpty());
            revisar("pagos guardados: Nequi 2.000 y efectivo neto 5.000 (recibio 10.000, vueltas 5.000)",
                    bd.pagosDeVenta(n2).equals(java.util.Map.of(MetodoPago.NEQUI, 2000L, MetodoPago.EFECTIVO, 5000L)));
            revisar("siguiente numero de venta", bd.siguienteNumero() == 3);

            Carrito sinStock = new Carrito();
            sinStock.agregar(cat.buscarPorId(chicle.getId()));
            String[] msg = {null};
            boolean rechazo = lanza(IllegalStateException.class, null, () -> {
                try { bd.cobrar(null, cajero, null, sinStock, pagos(MetodoPago.EFECTIVO, 200L), Cobro.calcular(200, pagos(MetodoPago.EFECTIVO, 200L))); }
                catch (IllegalStateException e) { msg[0] = e.getMessage(); throw e; }
                catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            });
            revisar("no se cobra lo que no hay (" + msg[0] + ")", rechazo && msg[0] != null && msg[0].startsWith("Ya no hay suficiente")
                    && msg[0].contains("Chicle") && bd.siguienteNumero() == 3 && bd.buscarProductoPorId(chicle.getId()).getStock() == 0);
            Carrito dosLineas = new Carrito();
            dosLineas.agregar(cat.buscarPorId(arroz.getId()));
            dosLineas.agregar(cat.buscarPorId(arroz.getId()), 1000);
            dosLineas.agregar(new Producto(arroz.getId(), arroz.getCodigo(), arroz.getNombre(), Unidad.UNIDAD, 2500, 2000, 1000, 0, null, true));
            long totalDos = dosLineas.getTotal();
            revisar("la suma de varias lineas del mismo producto tambien se revisa", lanza(IllegalStateException.class, null, () -> {
                try { bd.cobrar(null, cajero, null, dosLineas, pagos(MetodoPago.EFECTIVO, totalDos), Cobro.calcular(totalDos, pagos(MetodoPago.EFECTIVO, totalDos))); }
                catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            }) && bd.buscarProductoPorId(arroz.getId()).getStock() == 1000);

            Carrito malo = new Carrito();
            malo.agregar(arroz);
            malo.agregar(new Producto(999_999, null, "Fantasma", Unidad.UNIDAD, 100, 50, 0, 0, null, true));
            Cobro.Resultado rm = Cobro.calcular(malo.getTotal(), pagos(MetodoPago.EFECTIVO, 5000L));
            boolean fallo = lanza(Exception.class, null, () -> {
                try { bd.cobrar(null, cajero, null, malo, pagos(MetodoPago.EFECTIVO, 5000L), rm); }
                catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            });
            revisar("si algo falla al cobrar no queda nada a medias", fallo
                    && bd.buscarProductoPorId(arroz.getId()).getStock() == 1000 && bd.siguienteNumero() == 3);
            revisar("no se cobra si el pago no alcanza", lanza(IllegalArgumentException.class, null, () -> {
                Carrito cc = new Carrito();
                cc.agregar(arroz);
                try { bd.cobrar(null, cajero, null, cc, pagos(MetodoPago.EFECTIVO, 1L), Cobro.calcular(2500, pagos(MetodoPago.EFECTIVO, 1L))); }
                catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            }));

            Carrito c3 = new Carrito();
            c3.agregar(chicle);
            long id3 = bd.guardarEspera(null, null, cajero, c3);
            cat.desactivar(chicle.getId());
            BaseTienda.Retomada r3 = bd.cargarEspera(id3, cat);
            revisar("espera con producto desactivado se carga con aviso", r3.getCarrito().cantidadProductos() == 1
                    && r3.getAvisos().size() == 1 && r3.getAvisos().get(0).contains("Chicle"));
            bd.borrarEspera(id3);
            revisar("borrar espera", bd.listarEspera().isEmpty());
        }

        // base creada por la parte 1 (sin categorias): se actualiza sola
        java.io.File carpeta = Files.createTempDirectory("domiruta_vieja").toFile();
        java.io.File archivo = new java.io.File(carpeta, "tienda.db");
        try (java.sql.Connection con = java.sql.DriverManager.getConnection("jdbc:sqlite:" + archivo.getAbsolutePath());
             java.sql.Statement st = con.createStatement()) {
            st.execute("CREATE TABLE productos (id INTEGER PRIMARY KEY, codigo TEXT UNIQUE, nombre TEXT NOT NULL,"
                    + " clave_nombre TEXT NOT NULL, unidad TEXT NOT NULL, precio_venta INTEGER NOT NULL,"
                    + " precio_compra INTEGER NOT NULL, stock INTEGER NOT NULL DEFAULT 0, minimo INTEGER NOT NULL DEFAULT 0,"
                    + " foto TEXT, activo INTEGER NOT NULL DEFAULT 1, actualizado TEXT NOT NULL)");
            st.execute("INSERT INTO productos(nombre, clave_nombre, unidad, precio_venta, precio_compra, actualizado)"
                    + " VALUES('Pan', 'pan', 'UNIDAD', 300, 200, 'x')");
        }
        try (BaseTienda vieja = new BaseTienda(archivo, 1000)) {
            Categoria pan = vieja.crearCategoria("Panadería", null, 0);
            Catalogo cv = new Catalogo(vieja);
            Producto p = cv.buscarNombre("pan", 0).get(0);
            cv.editar(new Producto(p.getId(), null, "Pan", Unidad.UNIDAD, 300, 200, 0, 0, null, true, pan.getId()));
            revisar("base de la parte 1 recibe la columna de categoria", new Catalogo(vieja).buscarNombre("pan", 0).get(0)
                    .getCategoriaId() == pan.getId());
        }
    }

    private static void probarImportarCategorias() throws Exception {
        System.out.println("--- Importar con categoria ---");
        try (BaseTienda bd = baseTemporal()) {
            Categoria bebidas = bd.crearCategoria("Bebidas", null, 0);
            Catalogo cat = new Catalogo(bd);
            File csv = new File(Files.createTempDirectory("domiruta_cat").toFile(), "l.csv");
            Files.write(csv.toPath(), ("nombre;precio_venta;categoria\nGaseosa;2000;bebidas\nJabón;3000;Aseo\n"
                    + "Pan;300;\n").getBytes("UTF-8"));
            ImportadorProductos.Resultado r = ImportadorProductos.importar(csv, cat);
            revisar("importa con categoria existente (sin importar mayusculas)", r.creados == 3
                    && cat.buscarNombre("gaseosa", 0).get(0).getCategoriaId() == bebidas.getId());
            revisar("categoria nueva se crea sola", bd.listarCategorias(false).size() == 2
                    && cat.buscarNombre("jabon", 0).get(0).getCategoriaId() != null);
            revisar("sin categoria queda vacia", cat.buscarNombre("pan", 0).get(0).getCategoriaId() == null);
        }
    }

    private static void probarListado() throws Exception {
        System.out.println("--- Listado de productos (filtros y paginas) ---");
        try (BaseTienda bd = baseTemporal()) {
            Catalogo cat = new Catalogo(bd);
            long granos = bd.crearCategoria("Granos", null, 0).getId();
            Producto arroz = cat.crear(new Producto(0, "7701", "Arroz Diana", Unidad.UNIDAD, 2500, 2000, 24000, 6000, null, true, granos));
            cat.crear(new Producto(0, null, "Azúcar", Unidad.LIBRA, 2200, 1800, 2000, 5000, null, true, granos));
            cat.crear(new Producto(0, "7703", "Gaseosa", Unidad.UNIDAD, 2000, 1500, -3000, 6000, null, true, null));
            Producto jabon = cat.crear(new Producto(0, "7704", "Jabón Rey", Unidad.UNIDAD, 1800, 1200, 10000, 2000, null, true, null));
            cat.desactivar(jabon.getId());
            java.util.List<Producto> todos = cat.todosConInactivos();

            revisar("activos: 3", rutas.logica.ListadoProductos.filtrar(todos, "", null,
                    rutas.logica.ListadoProductos.Mostrar.ACTIVOS).size() == 3);
            revisar("bajo el minimo: azucar y gaseosa", rutas.logica.ListadoProductos.filtrar(todos, "", null,
                    rutas.logica.ListadoProductos.Mostrar.BAJO_MINIMO).size() == 2);
            revisar("sin existencias: solo la gaseosa (negativa)", rutas.logica.ListadoProductos.filtrar(todos, "", null,
                    rutas.logica.ListadoProductos.Mostrar.SIN_EXISTENCIAS).get(0).getNombre().equals("Gaseosa"));
            revisar("desactivados: el jabon", rutas.logica.ListadoProductos.filtrar(todos, "", null,
                    rutas.logica.ListadoProductos.Mostrar.DESACTIVADOS).get(0).getId() == jabon.getId());
            revisar("todos: 4", rutas.logica.ListadoProductos.filtrar(todos, "", null,
                    rutas.logica.ListadoProductos.Mostrar.TODOS).size() == 4);
            revisar("por categoria Granos: 2", rutas.logica.ListadoProductos.filtrar(todos, "", granos,
                    rutas.logica.ListadoProductos.Mostrar.ACTIVOS).size() == 2);
            revisar("buscar por parte del nombre sin tilde: 'azuc'", rutas.logica.ListadoProductos.filtrar(todos, "azuc", null,
                    rutas.logica.ListadoProductos.Mostrar.ACTIVOS).size() == 1);
            revisar("buscar 'diana' (en medio del nombre)", rutas.logica.ListadoProductos.filtrar(todos, "diana", null,
                    rutas.logica.ListadoProductos.Mostrar.ACTIVOS).get(0).getId() == arroz.getId());
            revisar("buscar por codigo exacto", rutas.logica.ListadoProductos.filtrar(todos, " 7703 ", null,
                    rutas.logica.ListadoProductos.Mostrar.TODOS).size() == 1);

            java.util.List<Producto> muchos = new java.util.ArrayList<>();
            for (int i = 0; i < 23; i++) {
                muchos.add(new Producto(i + 1, null, "P" + i, Unidad.UNIDAD, 1, 1, 1, 0, null, true));
            }
            rutas.logica.ListadoProductos.Pagina pg = rutas.logica.ListadoProductos.pagina(muchos, 10, 3);
            revisar("pagina 3 de 10 en 23: 3 productos, 3 paginas, desde 21",
                    pg.getProductos().size() == 3 && pg.getTotalPaginas() == 3 && pg.getDesde() == 21 && pg.getNumero() == 3);
            revisar("pagina fuera de rango se ajusta a la ultima", rutas.logica.ListadoProductos.pagina(muchos, 10, 9).getNumero() == 3);
            revisar("lista vacia: 1 pagina y 0 productos", rutas.logica.ListadoProductos.pagina(new java.util.ArrayList<>(), 10, 1)
                    .getTotalPaginas() == 1);
            revisar("botones de pagina al inicio: 1 2 3 4 5 ... 311", rutas.logica.ListadoProductos.botones(1, 311)
                    .equals(java.util.List.of(1, 2, 3, 4, 5, -1, 311)));
            revisar("botones en la mitad: 1 ... 148 149 150 151 152 ... 311", rutas.logica.ListadoProductos.botones(150, 311)
                    .equals(java.util.List.of(1, -1, 148, 149, 150, 151, 152, -1, 311)));
            revisar("botones al final: 1 ... 307 308 309 310 311", rutas.logica.ListadoProductos.botones(311, 311)
                    .equals(java.util.List.of(1, -1, 307, 308, 309, 310, 311)));
            revisar("pocas paginas: todas", rutas.logica.ListadoProductos.botones(2, 4).equals(java.util.List.of(1, 2, 3, 4)));

            java.util.Map<Long, String> nombresCat = new java.util.HashMap<>();
            nombresCat.put(granos, "Granos");
            String csv = rutas.logica.ListadoProductos.aCsv(todos, nombresCat);
            String sinCosto = rutas.logica.ListadoProductos.aCsv(todos, nombresCat, false);
            revisar("Excel para quien no ve costos: sin la columna precio_compra", csv.contains("precio_compra")
                    && !sinCosto.contains("precio_compra") && sinCosto.split("\r\n").length == csv.split("\r\n").length);
            File carpeta = java.nio.file.Files.createTempDirectory("domiruta_export").toFile();
            File archivo = new File(carpeta, "productos.csv");
            java.nio.file.Files.write(archivo.toPath(), csv.getBytes("UTF-8"));
            try (BaseTienda otra = baseTemporal()) {
                otra.crearCategoria("Granos", null, 0);
                Catalogo cat2 = new Catalogo(otra);
                ImportadorProductos.Resultado r = ImportadorProductos.importar(archivo, cat2);
                Producto az = cat2.buscarNombre("azucar", 1).get(0);
                // un stock negativo (por ventas) no se pasa a una tienda nueva: se pide corregirlo
                revisar("exportar e importar en otra tienda: el stock negativo se informa: " + r.creados + " " + r.errores,
                        r.creados == 3 && r.errores.size() == 1 && r.errores.get(0).contains("no puede ser negativo")
                        && az.getStock() == 2000 && az.getUnidad() == Unidad.LIBRA
                        && az.getPrecioVenta() == 2200 && az.getCategoriaId() != null
                        && cat2.buscarCodigo("7703") == null);
            }
            // en la misma tienda, la lista exportada se vuelve a importar igual (el negativo no cambio)
            ImportadorProductos.Resultado mismo = ImportadorProductos.importar(archivo, cat);
            revisar("volver a importar la lista exportada en la misma tienda: " + mismo.actualizados + " " + mismo.errores,
                    mismo.errores.stream().noneMatch(e -> e.contains("negativo")) && mismo.actualizados == 2
                    && cat.buscarCodigo("7703").getStock() == -3000);
        }
    }

    private static void probarArchivos() {
        System.out.println("--- Nombre del archivo guardado ---");
        File d = new File("/tmp");
        revisar("sin extension se le pone .csv", rutas.vista.Archivos.conExtension(new File(d, "productos"), "csv")
                .getName().equals("productos.csv"));
        revisar("con .CSV en mayusculas se deja igual", rutas.vista.Archivos.conExtension(new File(d, "Lista.CSV"), "csv")
                .getName().equals("Lista.CSV"));
        revisar("con otra extension se agrega .csv", rutas.vista.Archivos.conExtension(new File(d, "lista.txt"), "csv")
                .getName().equals("lista.txt.csv"));
    }

    private static void probarExistencias() {
        System.out.println("--- Existencias al vender ---");
        Producto aceite = new Producto(7, "77", "Aceite", Unidad.UNIDAD, 5500, 4000, 2000, 1000, null, true);
        rutas.logica.Existencias.Revision r1 = rutas.logica.Existencias.revisar(aceite, 1000);
        revisar("1 de 2 con minimo 1: quedan pocas (" + r1.getMensaje() + ")",
                r1.getEstado() == rutas.logica.Existencias.Estado.POCAS && r1.getMensaje().equals("Quedan 1 und de Aceite"));
        rutas.logica.Existencias.Revision r2 = rutas.logica.Existencias.revisar(aceite, 2000);
        revisar("2 de 2: se puede, y avisa que se acaba (" + r2.getMensaje() + ")",
                r2.puedeVender() && r2.getEstado() == rutas.logica.Existencias.Estado.POCAS
                && r2.getMensaje().equals("Con esta venta se acaba el Aceite"));
        rutas.logica.Existencias.Revision r3 = rutas.logica.Existencias.revisar(aceite, 6000);
        revisar("6 de 2: no alcanza (" + r3.getMensaje() + ")", !r3.puedeVender()
                && r3.getEstado() == rutas.logica.Existencias.Estado.NO_ALCANZA && r3.getMensaje().equals("Solo quedan 2 und de Aceite"));
        Producto agotado = new Producto(8, null, "Panela", Unidad.UNIDAD, 2000, 1500, 0, 0, null, true);
        rutas.logica.Existencias.Revision r4 = rutas.logica.Existencias.revisar(agotado, 1000);
        revisar("stock 0: agotado (" + r4.getMensaje() + ")", !r4.puedeVender()
                && r4.getEstado() == rutas.logica.Existencias.Estado.AGOTADO && r4.getMensaje().equals("No hay Panela: está agotado"));
        Producto mucho = new Producto(9, null, "Arroz", Unidad.UNIDAD, 2500, 2000, 50_000, 6000, null, true);
        revisar("con bastante stock: bien", rutas.logica.Existencias.revisar(mucho, 3000).getEstado()
                == rutas.logica.Existencias.Estado.BIEN);
        Producto granel = new Producto(10, null, "Azúcar", Unidad.LIBRA, 2200, 1800, 1500, 0, null, true);
        rutas.logica.Existencias.Revision r5 = rutas.logica.Existencias.revisar(granel, 2000);
        revisar("granel: 2 lb de 1,5 lb no alcanza (" + r5.getMensaje() + ")", !r5.puedeVender()
                && r5.getMensaje().equals("Solo quedan 1,5 lb de Azúcar"));
        Carrito c = new Carrito();
        c.agregar(aceite);
        c.agregar(granel, 500);
        c.agregar(aceite);
        revisar("lo que ya hay en la venta de ese producto", rutas.logica.Existencias.enVenta(c, aceite.getId()) == 2000);
    }
    private static void probarPorcentajeYNota() throws Exception {
        System.out.println("--- Descuento en % y nota de la venta ---");
        revisar("leer porcentaje: 10 -> 1000", Carrito.leerPorcentaje("10") == 1000);
        revisar("leer porcentaje: 12,5 % -> 1250", Carrito.leerPorcentaje(" 12,5 %") == 1250);
        revisar("leer porcentaje: vacio -> 0", Carrito.leerPorcentaje("") == 0);
        revisar("porcentaje de mas de 100 se rechaza", lanza(IllegalArgumentException.class, "El descuento va de 0 a 100 %",
                () -> Carrito.leerPorcentaje("120")));
        revisar("porcentaje con letras se rechaza", lanza(IllegalArgumentException.class, "Escriba el porcentaje con números, por ejemplo 10 o 12,5",
                () -> Carrito.leerPorcentaje("diez")));
        revisar("texto del porcentaje: 1250 -> 12,5", Carrito.textoPorcentaje(1250).equals("12,5"));
        revisar("texto del porcentaje: 0 -> 0", Carrito.textoPorcentaje(0).equals("0"));

        Producto jabon = new Producto(20, null, "Jabón", Unidad.UNIDAD, 4000, 3000, 50_000, 0, null, true);
        Carrito c = new Carrito();
        c.agregar(jabon);
        c.cambiarPorcentaje(0, 1000);
        LineaVenta l = c.getLineas().get(0);
        revisar("10 % de 4.000 = 400 de descuento", l.getDescuento() == 400 && l.getSubtotal() == 3600);
        c.cambiarCantidad(0, 3000);
        revisar("al subir la cantidad el % se mantiene (1.200 de descuento)", l.getDescuento() == 1200
                && l.getPorcentaje() == 1000 && c.getTotal() == 10_800);
        c.cambiarPrecio(0, 5000);
        revisar("al cambiar el precio el % se mantiene", l.getDescuento() == 1500);
        c.cambiarPorcentaje(0, 0);
        revisar("0 % quita el descuento", l.getDescuento() == 0 && c.getTotal() == 15_000);
        revisar("porcentaje fuera de rango en el carrito se rechaza", lanza(IllegalArgumentException.class, "El descuento va de 0 a 100 %",
                () -> c.cambiarPorcentaje(0, 10_001)));

        c.setNota("  Paga el viernes  ");
        revisar("la nota se guarda sin espacios de mas", c.getNota().equals("Paga el viernes"));
        c.setNota("   ");
        revisar("nota vacia queda en null", c.getNota() == null);
        StringBuilder larga = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            larga.append('x');
        }
        c.setNota(larga.toString());
        revisar("la nota se corta en 200 letras", c.getNota().length() == 200);

        try (BaseTienda bd = baseTemporal()) {
            long cajero = bd.crearUsuario("caja", "Caja", Rol.CAJERO, "clave123".toCharArray()).getId();
            Catalogo cat = new Catalogo(bd);
            Producto p = cat.crear(new Producto(0, null, "Jabón", Unidad.UNIDAD, 4000, 3000, 50_000, 0, null, true));
            Carrito e = new Carrito();
            e.agregar(p);
            e.agregar(p);
            e.cambiarPorcentaje(0, 1000);
            e.setNota("Lleva fiado el resto");
            long id = bd.guardarEspera(null, "Rosa", cajero, e);
            BaseTienda.Retomada r = bd.cargarEspera(id, cat);
            revisar("la espera recuerda el % y la nota", r.getCarrito().getLineas().get(0).getPorcentaje() == 1000
                    && r.getCarrito().getTotal() == 7200 && "Lleva fiado el resto".equals(r.getCarrito().getNota()));
            Cobro.Resultado res = Cobro.calcular(e.getTotal(), pagos(MetodoPago.EFECTIVO, 7200L));
            long numero = bd.cobrar(id, cajero, null, e, pagos(MetodoPago.EFECTIVO, 7200L), res);
            revisar("la venta cobrada guarda la nota", "Lleva fiado el resto".equals(bd.notaDeVenta(numero)));
            // descuento en pesos (ventas en espera de antes del %): al retomarla no cambia el total
            Producto tv = cat.crear(new Producto(0, null, "Televisor", Unidad.UNIDAD, 100_000, 80_000, 5000, 0, null, true));
            Carrito vieja = new Carrito();
            vieja.agregar(tv);
            vieja.cambiarDescuento(0, 123);
            long idv = bd.guardarEspera(null, null, cajero, vieja);
            Carrito vuelta = bd.cargarEspera(idv, cat).getCarrito();
            revisar("descuento en pesos se retoma igual (99.877)", vuelta.getTotal() == 99_877
                    && vuelta.getLineas().get(0).getPorcentajeGuardado() == 0);
            long idv2 = bd.guardarEspera(idv, null, cajero, vuelta);
            revisar("y sigue igual si se guarda otra vez en espera", bd.cargarEspera(idv2, cat).getCarrito().getTotal() == 99_877);
            vuelta.cambiarPorcentaje(0, 0);
            revisar("escribir 0 % quita un descuento que estaba en pesos", vuelta.getTotal() == 100_000);
        }
    }


    private static void probarCodigoBarras() {
        System.out.println("--- Código de barras ---");
        revisar("solo numeros usa el juego C con su control", rutas.vista.CodigoBarras.simbolos("12")
                .equals(java.util.List.of(105, 12, 14)));
        revisar("con letras usa el juego B", rutas.vista.CodigoBarras.simbolos("A1").get(0) == 104);
        revisar("digito EAN bien calculado", rutas.vista.CodigoBarras.digitoEan("750103131130") == 9);
        String c = rutas.vista.CodigoBarras.generar(x -> false);
        revisar("el generado tiene 13 numeros y empieza por 20", c.matches("20\\d{11}"));
        revisar("el generado trae su digito EAN", rutas.vista.CodigoBarras.digitoEan(c.substring(0, 12)) == c.charAt(12) - '0');
        java.util.Set<String> usados = new java.util.HashSet<>();
        String primero = rutas.vista.CodigoBarras.generar(x -> false);
        usados.add(primero);
        revisar("no repite un codigo que ya esta usado", !rutas.vista.CodigoBarras.generar(usados::contains).equals(primero));
        revisar("no dibuja la ñ ni tildes", !rutas.vista.CodigoBarras.sePuede("Año") && !rutas.vista.CodigoBarras.sePuede(" "));
        revisar("si dibuja letras y numeros comunes", rutas.vista.CodigoBarras.sePuede("ABC-123"));
    }


    private static void probarFichaProducto() throws Exception {
        System.out.println("--- Ficha completa del producto ---");
        try (BaseTienda bd = baseTemporal()) {
            long cajero = bd.crearUsuario("caja2", "Caja", Rol.CAJERO, "clave123".toCharArray()).getId();
            Catalogo cat = new Catalogo(bd);
            Producto aceite = cat.crear(new Producto(0, "02234672", "Aceite de oliva", Unidad.UNIDAD, 10000, 8000,
                    71000, 5000, null, true, null));
            revisar("la bodega punto de venta ya existe", bd.listarBodegas().size() == 1
                    && bd.listarBodegas().get(0).getNombre().equals("PUNTO DE VENTA"));
            rutas.modelo.Bodega b2 = bd.crearBodega("planta angela");
            revisar("bodega nueva en mayusculas", b2.getNombre().equals("PLANTA ANGELA") && bd.listarBodegas().size() == 2);
            revisar("no deja dos bodegas con el mismo nombre", lanzaCon(IllegalArgumentException.class, "Ya hay una bodega",
                    () -> { try { bd.crearBodega("Planta Angela"); } catch (java.sql.SQLException e) { throw new RuntimeException(e); } }));

            rutas.modelo.FichaProducto f = new rutas.modelo.FichaProducto();
            f.descripcion = "Aceite a base de ingredientes naturales";
            f.garantia = "Cambio si llega roto";
            f.medida = "Litro";
            f.impuestos.add("IVA 5%");
            f.vencimiento = java.time.LocalDate.of(2027, 3, 15);
            f.porMargen = true;
            f.margenCentesimas = 2500;
            f.precioMayor = 9500;
            f.esInsumo = true;
            f.urlImagen = "https://ejemplo.com/aceite.png";
            f.caracterizar = true;
            f.caracteristicas.add(new String[]{"Tamaño", "1 litro"});
            f.usaImei = true;
            f.imeis.add("490154203237518");
            f.usaLotes = true;
            f.lotes.add(new rutas.modelo.Lote("L-01", java.time.LocalDate.of(2026, 12, 1), 10000));
            f.lotes.add(new rutas.modelo.Lote("L-02", java.time.LocalDate.of(2026, 11, 5), 0));
            f.bodegas.put(b2.getId(), new long[]{4000, 1000});
            f.validar(10000);
            bd.guardarFicha(aceite.getId(), f);
            rutas.modelo.FichaProducto g = bd.leerFicha(aceite.getId());
            revisar("se guardan y se leen los textos", g.descripcion.equals(f.descripcion) && g.garantia.equals(f.garantia)
                    && g.medida.equals("Litro") && g.urlImagen.equals(f.urlImagen));
            revisar("se guardan impuesto, vencimiento y margen", g.impuestos.equals(java.util.List.of("IVA 5%"))
                    && g.vencimiento.equals(f.vencimiento) && g.porMargen && g.margenCentesimas == 2500 && g.precioMayor == 9500);
            revisar("se guarda el tipo", g.esProducto && g.esInsumo && !g.esReceta);
            revisar("se guardan caracteristicas, IMEI y lotes", g.caracteristicas.size() == 1
                    && g.caracteristicas.get(0)[1].equals("1 litro") && g.imeis.equals(f.imeis) && g.lotes.size() == 2);
            revisar("se guarda el stock de la otra bodega", g.bodegas.get(b2.getId())[0] == 4000
                    && g.bodegas.get(b2.getId())[1] == 1000);
            revisar("vence primero el lote con cantidad", g.vencimientoMasCercano().equals(java.time.LocalDate.of(2026, 12, 1)));
            revisar("listado de vencimientos usa el lote", bd.vencimientos().get(aceite.getId())
                    .equals(java.time.LocalDate.of(2026, 12, 1)));
            revisar("producto sin ficha toma su medida de la unidad", bd.leerFicha(999).medida.equals("Unidad"));

            revisar("IMEI valido", rutas.modelo.FichaProducto.imeiValido("490154203237518"));
            revisar("IMEI con digito malo se rechaza", !rutas.modelo.FichaProducto.imeiValido("490154203237519")
                    && !rutas.modelo.FichaProducto.imeiValido("12345"));
            Producto otro = cat.crear(new Producto(0, null, "Celular", Unidad.UNIDAD, 500000, 400000, 1000, 0, null, true, null));
            rutas.modelo.FichaProducto fo = new rutas.modelo.FichaProducto();
            fo.usaImei = true;
            fo.imeis.add("490154203237518");
            revisar("un IMEI no puede estar en dos productos", lanzaCon(IllegalArgumentException.class, "ya está en",
                    () -> { try { bd.guardarFicha(otro.getId(), fo); } catch (java.sql.SQLException e) { throw new RuntimeException(e); } }));
            revisar("si falla no queda nada a medias", !bd.leerFicha(otro.getId()).usaImei);

            rutas.modelo.FichaProducto mala = new rutas.modelo.FichaProducto();
            mala.precioMayor = 12000;
            revisar("por mayor mas caro que el de venta se rechaza", lanzaCon(IllegalArgumentException.class, "por mayor",
                    () -> mala.validar(10000)));
            rutas.modelo.FichaProducto sinTipo = new rutas.modelo.FichaProducto();
            sinTipo.esProducto = false;
            revisar("sin tipo se rechaza", lanzaCon(IllegalArgumentException.class, "Marque", () -> sinTipo.validar(0)));
            rutas.modelo.FichaProducto urlMala = new rutas.modelo.FichaProducto();
            urlMala.urlImagen = "foto.png";
            revisar("direccion de imagen sin http se rechaza", lanzaCon(IllegalArgumentException.class, "http",
                    () -> urlMala.validar(0)));
            revisar("precio con margen del 25 %", rutas.modelo.FichaProducto.precioConMargen(8000, 2500) == 10000);
            revisar("litro se vende por unidad y kilo por peso", rutas.modelo.FichaProducto.unidadDe("Litro") == Unidad.UNIDAD
                    && rutas.modelo.FichaProducto.unidadDe("Kilo") == Unidad.KILO);

            Producto servicio = cat.crear(new Producto(0, null, "Domicilio", Unidad.UNIDAD, 3000, 0, 0, 0, null, true,
                    null, false));
            revisar("sin inventario se puede vender aunque el stock sea 0",
                    rutas.logica.Existencias.revisar(servicio, 5000).puedeVender());
            revisar("sin inventario no sale como bajo minimo ni agotado", !servicio.estaBajoMinimo());
            Carrito c = new Carrito();
            c.agregar(servicio);
            c.agregar(aceite);
            Cobro.Resultado res = Cobro.calcular(c.getTotal(), pagos(MetodoPago.EFECTIVO, 20000L));
            bd.cobrar(null, cajero, null, c, pagos(MetodoPago.EFECTIVO, 20000L), res);
            revisar("cobrar un servicio no toca su stock", bd.buscarProductoPorId(servicio.getId()).getStock() == 0
                    && !bd.buscarProductoPorId(servicio.getId()).isManejaInventario());
            revisar("pero el aceite si baja", bd.buscarProductoPorId(aceite.getId()).getStock() == 70000);
            revisar("la venta sale del lote que vence primero", bd.leerFicha(aceite.getId()).lotes.stream()
                    .filter(l -> l.getNumero().equals("L-01")).findFirst().get().getCantidad() == 9000);
            revisar("se sabe que producto tiene un IMEI", "Aceite de oliva".equals(bd.productoConImei("490154203237518", 0))
                    && bd.productoConImei("490154203237518", aceite.getId()) == null);
        }
    }


    private static void probarAjustesRecibo() throws Exception {
        System.out.println("--- Configuración: mi negocio e impresora ---");
        try (BaseTienda bd = baseTemporal()) {
            rutas.logica.AjustesRecibo d = rutas.logica.AjustesRecibo.leer(bd);
            revisar("sin configurar: 58 mm y 48 mm útiles (384 puntos)", d.anchoMm == 58 && d.anchoUtilMm() == 48
                    && d.puntosUtiles() == 384);
            d.nombre = "Tienda La Esquina";
            d.nit = "901543586-2";
            d.logo = "C:/logo.png";
            d.anchoMm = 80;
            d.margenIzqMm = 4;
            d.margenDerMm = 4;
            d.letra = rutas.logica.AjustesRecibo.Letra.GRANDE;
            d.impresora = "POS-80";
            d.abrirCajon = true;
            d.copias = 2;
            d.guardar(bd);
            rutas.logica.AjustesRecibo l = rutas.logica.AjustesRecibo.leer(bd);
            revisar("se guardan los datos del negocio", l.nombre.equals("Tienda La Esquina") && l.nit.equals("901543586-2")
                    && "C:/logo.png".equals(l.logo));
            revisar("se guarda la impresora", l.anchoMm == 80 && l.anchoUtilMm() == 72 && l.puntosUtiles() == 576
                    && l.letra == rutas.logica.AjustesRecibo.Letra.GRANDE && l.impresora.equals("POS-80") && l.abrirCajon
                    && l.copias == 2);
            revisar("margen común por rollo", rutas.logica.AjustesRecibo.margenComun(58) == 5
                    && rutas.logica.AjustesRecibo.margenComun(80) == 4);
            rutas.logica.AjustesRecibo mal = rutas.logica.AjustesRecibo.leer(bd);
            mal.anchoMm = 30;
            revisar("ancho imposible se rechaza", lanzaCon(IllegalArgumentException.class, "ancho del papel", mal::validar));
            rutas.logica.AjustesRecibo estrecho = rutas.logica.AjustesRecibo.leer(bd);
            estrecho.anchoMm = 40;
            estrecho.margenIzqMm = 6;
            estrecho.margenDerMm = 6;
            revisar("márgenes que no dejan espacio se rechazan", lanzaCon(IllegalArgumentException.class, "muy poco",
                    estrecho::validar));
            revisar("si falla, no se guarda nada", lanzaCon(IllegalArgumentException.class, "ancho",
                    () -> { try { mal.guardar(bd); } catch (java.sql.SQLException e) { throw new RuntimeException(e); } })
                    && rutas.logica.AjustesRecibo.leer(bd).anchoMm == 80);
        }
    }


    private static void probarCategoriasExcel() throws Exception {
        System.out.println("--- Categorías: Excel e importación ---");
        java.io.File x = java.io.File.createTempFile("categorias", ".xlsx");
        x.deleteOnExit();
        java.util.List<Object[]> filas = new java.util.ArrayList<>();
        filas.add(new Object[]{30L, "Bebidas & jugos", "", "ACTIVO", 1});
        filas.add(new Object[]{null, "Aseo <hogar>", null, "INACTIVO", 2});
        rutas.logica.Xlsx.escribir(x, "Categorías", new String[]{"codigo", "nombre", "url_imagen", "estado", "orden"}, filas);
        java.util.List<String[]> leidas = rutas.logica.Xlsx.leer(x);
        revisar("Excel: se escribe y se vuelve a leer igual", leidas.size() == 3 && leidas.get(0)[1].equals("nombre")
                && leidas.get(1)[0].equals("30") && leidas.get(1)[1].equals("Bebidas & jugos")
                && leidas.get(2)[0].isEmpty() && leidas.get(2)[1].equals("Aseo <hogar>") && leidas.get(2)[3].equals("INACTIVO"));
        try (BaseTienda bd = baseTemporal()) {
            Categoria vieja = bd.crearCategoria("Granos", null, 1);
            java.util.List<String[]> imp = new java.util.ArrayList<>();
            imp.add(new String[]{"Código", "Nombre", "URL de imagen", "Estado"});
            imp.add(new String[]{"30", "Bebidas", "https://ejemplo.com/b.png", "ACTIVO"});
            imp.add(new String[]{"", "granos", "", "INACTIVO"});
            imp.add(new String[]{"", "Aseo", "", ""});
            imp.add(new String[]{"", "", "", ""});
            rutas.logica.ImportadorCategorias.Resultado r = rutas.logica.ImportadorCategorias.importar(imp, bd);
            revisar("importar crea y actualiza", r.errores.isEmpty() && r.creadas == 2 && r.actualizadas == 1
                    && bd.listarCategorias(true).size() == 3);
            revisar("el código del archivo queda como código", bd.buscarCategoria(30) != null
                    && bd.buscarCategoria(30).getNombre().equals("Bebidas"));
            revisar("sin código, el mismo nombre se actualiza (no se duplica)", !bd.buscarCategoria(vieja.getId()).isActivo());
            revisar("la imagen queda para bajar", r.fotos.size() == 1 && r.fotos.get(0)[0].equals("30"));
            java.util.List<String[]> mal = new java.util.ArrayList<>();
            mal.add(new String[]{"codigo", "nombre", "estado"});
            mal.add(new String[]{"40", "Dulces", ""});
            mal.add(new String[]{"abc", "Panadería", ""});
            mal.add(new String[]{"40", "Lácteos", "talvez"});
            rutas.logica.ImportadorCategorias.Resultado r2 = rutas.logica.ImportadorCategorias.importar(mal, bd);
            revisar("con errores no guarda ninguna fila", r2.errores.size() == 2 && bd.buscarCategoria(40) == null);
            java.util.List<String[]> sinNombre = new java.util.ArrayList<>();
            sinNombre.add(new String[]{"codigo", "titulo"});
            revisar("sin columna nombre avisa", rutas.logica.ImportadorCategorias.importar(sinNombre, bd).errores.get(0)
                    .contains("nombre"));
            Catalogo cat = new Catalogo(bd);
            cat.crear(new Producto(0, null, "Gaseosa", Unidad.UNIDAD, 2000, 1500, 1000, 0, null, true, 30L));
            revisar("cuenta productos por categoría", bd.productosPorCategoria().get(30L) == 1);
            revisar("no deja borrar una categoría con productos", lanzaCon(IllegalArgumentException.class, "desactívela",
                    () -> { try { bd.eliminarCategoria(30); } catch (java.sql.SQLException e) { throw new RuntimeException(e); } }));
            long aseo = 0;
            for (Categoria c : bd.listarCategorias(true)) {
                if (c.getNombre().equals("Aseo")) {
                    aseo = c.getId();
                }
            }
            bd.eliminarCategoria(aseo);
            revisar("borra una categoría sin productos", bd.buscarCategoria(aseo) == null);
        }
    }

    private static void probarCompras() throws Exception {
        System.out.println("--- Compras ---");
        // calculos como en la referencia: 926 + 8 % = 1.000; total bruto 3.800, impuestos 74, total 3.874
        Producto a = new Producto(1, null, "Gaseosa", Unidad.UNIDAD, 1500, 1000, 0, 0, null, true);
        Producto b = new Producto(2, null, "Arroz", Unidad.UNIDAD, 3500, 2874, 0, 0, null, true);
        rutas.logica.Compra c = new rutas.logica.Compra();
        rutas.logica.Compra.Linea la = c.agregar(a, rutas.logica.Compra.impuestoDe(java.util.List.of("INC 8%")));
        c.agregar(b, 0);
        revisar("compra: 1.000 con 8 % queda base 926", la.getBase() == 926 && la.getCosto() == 1000);
        revisar("compra: lo nuevo queda arriba", c.getLineas().get(0).getProducto() == b);
        revisar("compra: totales de la referencia", c.getBruto() == 3800 && c.getImpuestos() == 74 && c.getTotal() == 3874);
        c.agregar(a, 800);
        revisar("compra: el mismo producto suma 1", la.getCantidad() == 2000 && c.getLineas().size() == 2);
        la.setDescuento(1000); // 10 %
        revisar("compra: descuento de linea", la.getValorDescuento() == 185 && la.getCostoNeto() == 900);
        revisar("compra: impuestos 19 % + 8 %", rutas.logica.Compra.impuestoDe(java.util.List.of("IVA 19%", "INC 8%")) == 2700
                && rutas.logica.Compra.impuestoDe(java.util.List.of("IVA 5,5 %")) == 550);
        revisar("compra vacia se rechaza", lanza(IllegalArgumentException.class, "Agregue al menos un producto a la compra",
                () -> new rutas.logica.Compra().validar()));
        la.setCantidad(0);
        revisar("compra con cantidad 0 se rechaza", lanzaCon(IllegalArgumentException.class, "debe ser mayor que 0", c::validar));
        la.setCantidad(2000);
        c.setDescuentoGeneral(999_999);
        revisar("descuento mas grande que la compra se rechaza", lanzaCon(IllegalArgumentException.class, "más grande", c::validar));
        c.setDescuentoGeneral(0);

        try (BaseTienda bd = baseTemporal()) {
            long admin = bd.crearUsuario("jefe", "Jefe", Rol.ADMIN, "clave123".toCharArray()).getId();
            Catalogo cat = new Catalogo(bd);
            Producto gaseosa = cat.crear(new Producto(0, "770100", "Gaseosa", Unidad.UNIDAD, 1500, 900, 2000, 0, null, true));
            Producto azucar = cat.crear(new Producto(0, null, "Azúcar", Unidad.KILO, 4400, 3600, -500, 0, null, true));
            rutas.logica.Compra k = new rutas.logica.Compra();
            rutas.logica.Compra.Linea lg = k.agregar(gaseosa, 800);
            lg.setCantidad(12_000);
            lg.setCosto(1080);
            lg.setPrecioVenta(1800L);
            rutas.logica.Compra.Linea lz = k.agregar(azucar, 0);
            lz.setCantidad(2500);
            lz.setBase(3800);
            k.setObservaciones("Llegó con la factura");
            k.setProveedor("Postobón");

            long idEspera = bd.guardarCompraEspera(null, admin, k);
            revisar("compra en espera aparece en la lista", bd.listarComprasEspera().size() == 1
                    && bd.listarComprasEspera().get(0).getProductos() == 2
                    && bd.listarComprasEspera().get(0).getTotal() == k.getTotal()
                    && "Postobón".equals(bd.listarComprasEspera().get(0).getProveedor()));
            revisar("compra en espera no sube el stock", bd.buscarProductoPorId(gaseosa.getId()).getStock() == 2000);
            BaseTienda.CompraRetomada r = bd.cargarCompraEspera(idEspera, cat);
            rutas.logica.Compra k2 = r.getCompra();
            revisar("compra en espera vuelve igual", r.getAvisos().isEmpty() && k2.getLineas().size() == 2
                    && k2.getLineas().get(0).getProducto().getId() == azucar.getId()
                    && k2.getLineas().get(1).getCantidad() == 12_000 && k2.getLineas().get(1).getCosto() == 1080
                    && Long.valueOf(1800).equals(k2.getLineas().get(1).getPrecioVenta())
                    && k2.getTotal() == k.getTotal() && k2.getObservaciones().equals("Llegó con la factura"));
            bd.guardarCompraEspera(idEspera, admin, k2); // actualizarla no duplica
            revisar("actualizar la espera no la duplica", bd.listarComprasEspera().size() == 1
                    && bd.cargarCompraEspera(idEspera, cat).getCompra().getLineas().size() == 2);

            long n1 = bd.registrarCompra(idEspera, admin, k2);
            for (rutas.logica.Compra.Linea l : k2.getLineas()) {
                cat.recargarProducto(l.getProducto().getId());
            }
            Producto g2 = bd.buscarProductoPorId(gaseosa.getId());
            Producto z2 = bd.buscarProductoPorId(azucar.getId());
            revisar("compra guardada: numero 1 y sale de espera", n1 == 1 && bd.listarComprasEspera().isEmpty());
            revisar("compra sube el stock (tambien si estaba negativo)", g2.getStock() == 14_000 && z2.getStock() == 2000);
            revisar("compra deja el costo nuevo", g2.getPrecioCompra() == 1080 && z2.getPrecioCompra() == 3800);
            revisar("compra cambia el precio de venta si se escribio", g2.getPrecioVenta() == 1800 && z2.getPrecioVenta() == 4400);
            revisar("el catalogo queda al dia", cat.buscarPorId(gaseosa.getId()).getStock() == 14_000);
            revisar("ya guardada no se puede retomar", lanza(IllegalArgumentException.class, "Esa compra ya no está en espera",
                    () -> { try { bd.cargarCompraEspera(idEspera, cat); } catch (java.sql.SQLException e) { throw new RuntimeException(e); } }));

            // a otra bodega: no toca el stock del punto de venta
            rutas.modelo.Bodega trastienda = bd.crearBodega("Trastienda");
            rutas.logica.Compra k3 = new rutas.logica.Compra();
            k3.agregar(g2, 0).setCantidad(6000);
            k3.setBodegaId(trastienda.getId());
            long n2 = bd.registrarCompra(null, admin, k3);
            k3.getLineas().get(0).setCantidad(4000);
            bd.registrarCompra(null, admin, k3);
            revisar("compra a otra bodega suma en esa bodega", n2 == 2
                    && bd.leerFicha(gaseosa.getId()).bodegas.get(trastienda.getId())[0] == 10_000
                    && bd.buscarProductoPorId(gaseosa.getId()).getStock() == 14_000);

            // sin manejo de inventario: no sube stock pero si el costo
            Producto domicilio = cat.crear(new Producto(0, null, "Bolsa", Unidad.UNIDAD, 100, 50, 0, 0, null, true, null, false));
            rutas.logica.Compra k4 = new rutas.logica.Compra();
            k4.agregar(domicilio, 0).setCosto(60);
            bd.registrarCompra(null, admin, k4);
            revisar("sin inventario: no sube stock, si el costo", bd.buscarProductoPorId(domicilio.getId()).getStock() == 0
                    && bd.buscarProductoPorId(domicilio.getId()).getPrecioCompra() == 60);

            // si algo falla no se guarda nada
            rutas.logica.Compra k5 = new rutas.logica.Compra();
            k5.agregar(g2, 0);
            k5.agregar(new Producto(999_999, null, "Fantasma", Unidad.UNIDAD, 100, 50, 0, 0, null, true), 0);
            revisar("compra con producto que no existe se rechaza entera", lanzaCon(RuntimeException.class, "ya no existe",
                    () -> { try { bd.registrarCompra(null, admin, k5); } catch (java.sql.SQLException e) { throw new RuntimeException(e); } }));
            // costo escrito con 19 %: queda tal cual (no se corre un peso al redondear)
            rutas.logica.Compra k6 = new rutas.logica.Compra();
            rutas.logica.Compra.Linea l6 = k6.agregar(bd.buscarProductoPorId(gaseosa.getId()), 1900);
            l6.setCosto(3);
            revisar("costo escrito no se corre al redondear", l6.getCosto() == 3);
            // el precio de venta cambiado en Productos no se pisa con una copia vieja
            long idK6 = bd.guardarCompraEspera(null, admin, k6);
            cat.editar(new Producto(gaseosa.getId(), "770100", "Gaseosa", Unidad.UNIDAD, 2000, 1080, 14_000, 0, null, true));
            rutas.logica.Compra k6b = bd.cargarCompraEspera(idK6, cat).getCompra();
            revisar("espera guarda el costo escrito", k6b.getLineas().get(0).getCosto() == 3);
            bd.registrarCompra(idK6, admin, k6b);
            Producto g3 = bd.buscarProductoPorId(gaseosa.getId());
            revisar("precio de venta nuevo de Productos se respeta", g3.getPrecioVenta() == 2000 && g3.getPrecioCompra() == 3);
            // desactivado: no se puede comprar
            cat.desactivar(azucar.getId());
            rutas.logica.Compra k7 = new rutas.logica.Compra();
            k7.agregar(bd.buscarProductoPorId(azucar.getId()), 0);
            revisar("producto desactivado no se compra", lanzaCon(RuntimeException.class, "está desactivado",
                    () -> { try { bd.registrarCompra(null, admin, k7); } catch (java.sql.SQLException e) { throw new RuntimeException(e); } }));
            revisar("valor de venta 0 se rechaza", lanzaCon(IllegalArgumentException.class, "mayor que 0", () -> {
                rutas.logica.Compra k8 = new rutas.logica.Compra();
                k8.agregar(g3, 0).setPrecioVenta(0L);
                k8.validar();
            }));
            revisar("y no toco el stock ni el numero", bd.buscarProductoPorId(gaseosa.getId()).getStock() == 15_000
                    && bd.siguienteNumeroCompra() == 6);
        }
    }

    private static void probarHistorialCompras() throws Exception {
        System.out.println("--- Historial de compras ---");
        try (BaseTienda bd = baseTemporal()) {
            long admin = bd.crearUsuario("jefe", "Ana Jefe", Rol.ADMIN, "clave123".toCharArray()).getId();
            Catalogo cat = new Catalogo(bd);
            Producto leche = cat.crear(new Producto(0, "7701", "Leche", Unidad.UNIDAD, 3000, 2500, 0, 0, null, true));
            Producto pan = cat.crear(new Producto(0, null, "Pan", Unidad.UNIDAD, 500, 300, 0, 0, null, true));
            rutas.modelo.Bodega trastienda = bd.crearBodega("Trastienda");

            rutas.logica.Compra c1 = new rutas.logica.Compra();
            c1.agregar(leche, 0).setCantidad(10_000);
            c1.setProveedor("Alpina");
            c1.setFactura("FE-100");
            c1.setObservaciones("Llegó fría");
            long n1 = bd.registrarCompra(null, admin, c1);
            rutas.logica.Compra c2 = new rutas.logica.Compra();
            c2.agregar(pan, 0).setCantidad(20_000);
            c2.setBodegaId(trastienda.getId());
            c2.setProveedor("Panadería Rey");
            long n2 = bd.registrarCompra(null, admin, c2);
            bd.guardarCompraEspera(null, admin, c1); // las de espera no salen en el historial

            java.time.LocalDateTime hoy0 = java.time.LocalDate.now().atStartOfDay();
            java.time.LocalDateTime hoy1 = java.time.LocalDate.now().atTime(23, 59);
            java.util.List<BaseTienda.CompraGuardada> todas = bd.listarCompras(hoy0, hoy1, null, null, "");
            revisar("historial: salen las guardadas, la mas nueva primero", todas.size() == 2
                    && todas.get(0).getNumero() == n2 && todas.get(1).getNumero() == n1);
            revisar("historial: trae bodega, quien atendio y total", todas.get(1).getBodega().equals("PUNTO DE VENTA")
                    && todas.get(1).getAtendio().equals("Ana Jefe") && todas.get(1).getTotal() == 25_000
                    && todas.get(1).getFactura().equals("FE-100"));
            revisar("historial: filtro por bodega", bd.listarCompras(hoy0, hoy1, null, trastienda.getId(), "").size() == 1);
            revisar("historial: busca por proveedor", bd.listarCompras(hoy0, hoy1, null, null, "alpi").size() == 1);
            revisar("historial: busca por numero con ceros", bd.listarCompras(hoy0, hoy1, null, null, "0000" + n1).size() == 1);
            revisar("historial: busca por factura", bd.listarCompras(hoy0, hoy1, null, null, "FE-1").size() == 1);
            revisar("historial: ayer no hay compras", bd.listarCompras(hoy0.minusDays(1), hoy0.minusMinutes(1), null, null, "").isEmpty());
            revisar("historial: fechas al reves se rechazan", lanza(IllegalArgumentException.class,
                    "La fecha inicial es después de la fecha final", () -> {
                        try { bd.listarCompras(hoy1, hoy0, null, null, ""); } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
                    }));

            BaseTienda.DetalleCompra d = bd.detalleCompra(todas.get(1).getId());
            revisar("detalle: productos, observaciones y totales", d.lineas.size() == 1 && d.lineas.get(0).nombre.equals("Leche")
                    && d.lineas.get(0).cantidad == 10_000 && d.lineas.get(0).costo == 2500 && d.total == 25_000
                    && d.observaciones.equals("Llegó fría"));

            // vender 4 leches y anular: el punto de venta queda en -4 (se avisa antes)
            cat.editar(new Producto(leche.getId(), "7701", "Leche", Unidad.UNIDAD, 3000, 2500, 6000, 0, null, true));
            java.util.List<String> av = bd.revisarAnulacion(todas.get(1).getId());
            revisar("anular: avisa si el stock queda negativo", av.size() == 1 && av.get(0).contains("-4"));
            revisar("anular: pide el motivo", lanza(IllegalArgumentException.class, "Escriba por qué se anula la compra",
                    () -> { try { bd.anularCompra(todas.get(1).getId(), admin, " "); } catch (java.sql.SQLException e) { throw new RuntimeException(e); } }));
            bd.anularCompra(todas.get(1).getId(), admin, "Factura repetida");
            revisar("anular: devuelve el stock", bd.buscarProductoPorId(leche.getId()).getStock() == -4000);
            BaseTienda.DetalleCompra d2 = bd.detalleCompra(todas.get(1).getId());
            revisar("anular: queda quien, cuando y por que", d2.compra.isAnulada() && d2.anuladaPor.equals("Ana Jefe")
                    && d2.motivo.equals("Factura repetida") && d2.anuladaEn != null);
            revisar("anular dos veces no se puede", lanza(IllegalStateException.class, "Esa compra ya estaba anulada",
                    () -> { try { bd.anularCompra(todas.get(1).getId(), admin, "otra"); } catch (java.sql.SQLException e) { throw new RuntimeException(e); } }));
            revisar("historial: filtro anuladas y pagadas", bd.listarCompras(hoy0, hoy1, true, null, "").size() == 1
                    && bd.listarCompras(hoy0, hoy1, false, null, "").size() == 1);

            // otra bodega: no se saca mas de lo que hay
            bd.guardarFicha(pan.getId(), conBodega(bd.leerFicha(pan.getId()), trastienda.getId(), 5000));
            revisar("anular en bodega sin mercancia se rechaza entera", lanzaCon(IllegalArgumentException.class,
                    "solo quedan 5", () -> { try { bd.anularCompra(todas.get(0).getId(), admin, "error"); } catch (java.sql.SQLException e) { throw new RuntimeException(e); } }));
            revisar("y la compra sigue pagada", !bd.detalleCompra(todas.get(0).getId()).compra.isAnulada()
                    && bd.leerFicha(pan.getId()).bodegas.get(trastienda.getId())[0] == 5000);
            bd.guardarFicha(pan.getId(), conBodega(bd.leerFicha(pan.getId()), trastienda.getId(), 25_000));
            bd.anularCompra(todas.get(0).getId(), admin, "Devuelta");
            revisar("anular en otra bodega resta en esa bodega", bd.leerFicha(pan.getId()).bodegas.get(trastienda.getId())[0] == 5000
                    && bd.buscarProductoPorId(pan.getId()).getStock() == 0);
        }
    }

    private static rutas.modelo.FichaProducto conBodega(rutas.modelo.FichaProducto f, long bodega, long stock) {
        f.bodegas.put(bodega, new long[]{stock, 0});
        return f;
    }

    private static void probarMediosPago() throws Exception {
        System.out.println("--- Medios de pago ---");
        java.io.File carpeta = Files.createTempDirectory("domiruta_pagos").toFile();
        java.io.File archivo = new java.io.File(carpeta, "tienda.db");
        long cajero;
        try (BaseTienda bd = new BaseTienda(archivo, 1000)) {
            revisar("por defecto: los 4 de antes", rutas.logica.MediosPago.activos(bd).equals(java.util.List.of(
                    MetodoPago.EFECTIVO, MetodoPago.NEQUI, MetodoPago.DAVIPLATA, MetodoPago.TRANSFERENCIA)));
            rutas.logica.MediosPago.guardar(bd, java.util.List.of(MetodoPago.BREB, MetodoPago.NEQUI));
            revisar("efectivo siempre va y el orden es el de la lista", rutas.logica.MediosPago.activos(bd).equals(
                    java.util.List.of(MetodoPago.EFECTIVO, MetodoPago.NEQUI, MetodoPago.BREB)));
            cajero = bd.crearUsuario("caja", "Caja", Rol.CAJERO, "clave123".toCharArray()).getId();
            Catalogo cat = new Catalogo(bd);
            Producto p = cat.crear(new Producto(0, null, "Pan", Unidad.UNIDAD, 1000, 500, 10_000, 0, null, true));
            Carrito c = new Carrito();
            c.agregar(p);
            // una venta vieja pagada con Nequi, guardada con la tabla de antes
            long n = bd.cobrar(null, cajero, null, c, pagos(MetodoPago.NEQUI, 1000L),
                    Cobro.calcular(1000, pagos(MetodoPago.NEQUI, 1000L)));
            revisar("venta con Nequi", bd.pagosDeVenta(n).equals(java.util.Map.of(MetodoPago.NEQUI, 1000L)));
        }
        // se vuelve la tabla de pagos a como era antes (solo 4 medios)
        try (java.sql.Connection con = java.sql.DriverManager.getConnection("jdbc:sqlite:" + archivo.getAbsolutePath());
             java.sql.Statement st = con.createStatement()) {
            st.execute("CREATE TABLE vieja (id INTEGER PRIMARY KEY, venta_id INTEGER NOT NULL REFERENCES ventas(id)"
                    + " ON DELETE CASCADE, metodo TEXT NOT NULL CHECK (metodo IN ('EFECTIVO','NEQUI','DAVIPLATA',"
                    + "'TRANSFERENCIA')), valor INTEGER NOT NULL)");
            st.execute("INSERT INTO vieja SELECT * FROM venta_pagos");
            st.execute("DROP TABLE venta_pagos");
            st.execute("ALTER TABLE vieja RENAME TO venta_pagos");
        }
        try (BaseTienda bd = new BaseTienda(archivo, 1000)) {
            revisar("base vieja: los pagos de antes siguen", bd.pagosDeVenta(1).equals(java.util.Map.of(MetodoPago.NEQUI, 1000L)));
            Catalogo cat = new Catalogo(bd);
            Carrito c = new Carrito();
            c.agregar(cat.buscar("Pan").get(0));
            java.util.List<Pago> l = pagos(MetodoPago.BREB, 600L, MetodoPago.TARJETA_DEBITO, 400L);
            long n = bd.cobrar(null, cajero, null, c, l, Cobro.calcular(1000, l));
            revisar("base vieja ampliada: se cobra con Bre-B y tarjeta", bd.pagosDeVenta(n).equals(
                    java.util.Map.of(MetodoPago.BREB, 600L, MetodoPago.TARJETA_DEBITO, 400L)));
        }
        revisar("cada medio tiene su logo", java.util.Arrays.stream(MetodoPago.values())
                .allMatch(m -> PruebasCaja.class.getResource(m.getLogo()) != null)
                && PruebasCaja.class.getResource("/imagenes/pagos/combinado.png") != null);
    }

    private static void probarClientesCaja() throws Exception {
        System.out.println("--- Clientes de la caja ---");
        java.io.File carpeta = Files.createTempDirectory("domiruta_clientes").toFile();
        java.io.File archivo = new java.io.File(carpeta, "domiruta.db");
        // base vieja: la tabla de clientes sin documento ni correo
        try (java.sql.Connection con = java.sql.DriverManager.getConnection("jdbc:sqlite:" + archivo.getAbsolutePath());
             java.sql.Statement st = con.createStatement()) {
            st.execute("CREATE TABLE clientes (id INTEGER PRIMARY KEY AUTOINCREMENT, clave TEXT NOT NULL UNIQUE,"
                    + " nombre TEXT NOT NULL, telefono TEXT NOT NULL DEFAULT '', direccion TEXT NOT NULL,"
                    + " nota TEXT NOT NULL DEFAULT '', lat REAL NOT NULL, lon REAL NOT NULL,"
                    + " creado TEXT NOT NULL DEFAULT (datetime('now','localtime')))");
            st.execute("INSERT INTO clientes(clave, nombre, telefono, direccion, lat, lon)"
                    + " VALUES('ana ruiz', 'Ana Ruiz', '3001112233', 'Calle 1', 11.5, -72.9)");
        }
        try (rutas.logica.BaseDatos bd = new rutas.logica.BaseDatos(archivo)) {
            rutas.estructuras.ArbolAVL<String, rutas.modelo.Cliente> a = bd.cargarClientes();
            rutas.modelo.Cliente ana = a.buscar("ana ruiz");
            revisar("base vieja de clientes se amplia sin perder nada", ana != null && ana.getTelefono().equals("3001112233")
                    && ana.getDocumento().isEmpty() && ana.tieneUbicacion());
            bd.guardarCliente(new rutas.modelo.Cliente("Marcela Luna", "3127778899", "", "", 0, 0,
                    "Cédula de ciudadanía", "1052090292", "marce@gmail.com", "La Guajira", "Riohacha", 200_000));
            bd.guardarCliente(ana.conTelefono("3009998877"));
            a = bd.cargarClientes();
            rutas.modelo.Cliente m = a.buscar("marcela luna");
            revisar("cliente de la caja guarda documento, correo y fiado", m != null && m.getDocumento().equals("1052090292")
                    && m.getCorreo().equals("marce@gmail.com") && m.getCreditoMaximo() == 200_000
                    && m.documentoCorto().equals("CC 1052090292") && !m.tieneUbicacion());
            revisar("cambiar el celular conserva lo demas", a.buscar("ana ruiz").getTelefono().equals("3009998877")
                    && a.buscar("ana ruiz").getLat() == 11.5);
            revisar("buscar por cedula, celular y apellido", rutas.vista.PruebaAcceso.coincide(m, "105209")
                    && rutas.vista.PruebaAcceso.coincide(m, "312 777") && rutas.vista.PruebaAcceso.coincide(m, "luna")
                    && !rutas.vista.PruebaAcceso.coincide(m, "ana"));
        }
    }

    /** Servidor de correo de mentiras (en este computador) para probar el envio sin internet. */
    private static String[] servidorFalso(java.net.ServerSocket ss, String claveBuena) {
        String[] recibido = {null, null};
        Thread t = new Thread(() -> {
            try (java.net.Socket s = ss.accept();
                 java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(),
                         java.nio.charset.StandardCharsets.UTF_8));
                 java.io.PrintWriter out = new java.io.PrintWriter(new java.io.OutputStreamWriter(s.getOutputStream(),
                         java.nio.charset.StandardCharsets.UTF_8), true)) {
                out.print("220 hola\r\n"); out.flush();
                String l;
                int paso = 0;
                StringBuilder data = new StringBuilder();
                boolean enData = false;
                while ((l = in.readLine()) != null) {
                    if (enData) {
                        if (l.equals(".")) { enData = false; recibido[0] = data.toString(); out.print("250 ok\r\n"); out.flush(); }
                        else { data.append(l).append("\n"); }
                        continue;
                    }
                    if (l.startsWith("EHLO")) { out.print("250-hola\r\n250 AUTH LOGIN PLAIN\r\n"); }
                    else if (l.equals("AUTH LOGIN")) { out.print("334 VXNlcm5hbWU6\r\n"); paso = 1; }
                    else if (paso == 1) { out.print("334 UGFzc3dvcmQ6\r\n"); paso = 2; }
                    else if (paso == 2) {
                        String clave = new String(java.util.Base64.getDecoder().decode(l), java.nio.charset.StandardCharsets.UTF_8);
                        recibido[1] = clave;
                        out.print(clave.equals(claveBuena) ? "235 bien\r\n" : "535 mal\r\n"); paso = 3;
                    }
                    else if (l.startsWith("MAIL") || l.startsWith("RCPT")) { out.print("250 ok\r\n"); }
                    else if (l.equals("DATA")) { out.print("354 dale\r\n"); enData = true; }
                    else if (l.equals("QUIT")) { out.print("221 chao\r\n"); out.flush(); break; }
                    out.flush();
                }
            } catch (java.io.IOException e) {
                // fin
            }
        });
        t.setDaemon(true);
        t.start();
        return recibido;
    }

    private static void probarCorreo() throws Exception {
        System.out.println("--- Correo y PDF ---");
        String conHtml = rutas.logica.CorreoPrueba.mensaje("tienda@gmail.com", "Tienda", "cliente@gmail.com", "Recibo",
                "Hola,\nsu compra.", "<p>Hola</p>", "Recibo-00001.pdf", new byte[]{1, 2, 3}, "application/pdf");
        revisar("correo: lleva Message-ID y Reply-To (menos spam)", conHtml.contains("\r\nMessage-ID: <")
                && conHtml.contains("@gmail.com>\r\n") && conHtml.contains("Reply-To: Tienda <tienda@gmail.com>"));
        revisar("correo: texto y HTML juntos y el PDF aparte", conHtml.contains("multipart/alternative")
                && conHtml.contains("text/html; charset=UTF-8") && conHtml.contains("filename=\"Recibo-00001.pdf\""));
        String html = rutas.vista.PruebaAcceso.htmlRecibo("Hola <Ana> & cía");
        revisar("correo: el HTML lleva la compra y no deja pasar etiquetas escritas", html.contains("&lt;Ana&gt; &amp; cía")
                && html.contains("Total") && html.contains("Recibo de venta N.º") && html.contains("cid:logo@domiruta"));
        String conLogo = rutas.logica.CorreoPrueba.mensaje("tienda@gmail.com", "Tienda", "cliente@gmail.com", "Recibo",
                "Hola", "<img src=\"cid:logo@domiruta\">", new byte[]{9, 9}, "Recibo-00001.pdf", new byte[]{1}, "application/pdf");
        revisar("correo: el logo va dentro del correo (related + Content-ID) y el PDF aparte", conLogo.contains("multipart/related")
                && conLogo.contains("Content-ID: <logo@domiruta>") && conLogo.contains("Content-Disposition: inline")
                && conLogo.indexOf("Content-ID") < conLogo.indexOf("filename=\"Recibo-00001.pdf\""));
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(300, 500, java.awt.image.BufferedImage.TYPE_INT_RGB);
        byte[] pdf = rutas.logica.PdfImagen.crear(img, 164);
        String txt = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        revisar("PDF: empieza y termina bien", txt.startsWith("%PDF-1.4") && txt.trim().endsWith("%%EOF")
                && txt.contains("/MediaBox [0 0 164.00 273.33]"));

        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            String[] r = servidorFalso(ss, "abcdabcdabcdabcd");
            rutas.logica.CorreoSmtp c = new rutas.logica.CorreoSmtp("127.0.0.1", ss.getLocalPort(), false,
                    "tienda@gmail.com", "abcd abcd abcd abcd");
            c.enviar("Tienda La Esquiña", "cliente@gmail.com", "Recibo No. 00015 – gracias", "Hola,\nsu compra.",
                    "Recibo-00015.pdf", pdf, "application/pdf");
            Thread.sleep(200);
            String m = r[0];
            revisar("correo: la clave va sin espacios", "abcdabcdabcdabcd".equals(r[1]));
            revisar("correo: lleva remitente, para y asunto con tildes", m != null && m.contains("From: =?UTF-8?B?")
                    && m.contains("<tienda@gmail.com>") && m.contains("To: <cliente@gmail.com>") && m.contains("Subject: =?UTF-8?B?"));
            String parte = m.substring(m.indexOf("filename=\"Recibo-00015.pdf\""));
            String b64 = parte.substring(parte.indexOf("\n\n") + 2, parte.indexOf("------=_DomiRuta")).replace("\n", "");
            revisar("correo: el PDF adjunto llega igual", java.util.Arrays.equals(java.util.Base64.getDecoder().decode(b64), pdf));
        }
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            servidorFalso(ss, "otraclaveotracla");
            rutas.logica.CorreoSmtp c = new rutas.logica.CorreoSmtp("127.0.0.1", ss.getLocalPort(), false,
                    "tienda@gmail.com", "abcdabcdabcdabcd");
            revisar("correo: clave mala da un mensaje claro", lanzaCon(RuntimeException.class, "contraseña de aplicación", () -> {
                try { c.enviar("T", "cliente@gmail.com", "a", "b", null, null, null); }
                catch (rutas.logica.CorreoSmtp.ErrorCorreo e) { throw new RuntimeException(e.getMessage()); }
            }));
        }
        rutas.logica.CorreoSmtp sinConfig = rutas.logica.CorreoSmtp.gmail("", "");
        revisar("correo: sin configurar avisa", lanzaCon(RuntimeException.class, "Configuración › Correo", () -> {
            try { sinConfig.enviar("T", "cliente@gmail.com", "a", "b", null, null, null); }
            catch (rutas.logica.CorreoSmtp.ErrorCorreo e) { throw new RuntimeException(e.getMessage()); }
        }));
        try (BaseTienda bd = baseTemporal()) {
            rutas.logica.AjustesCorreo a = new rutas.logica.AjustesCorreo();
            a.cuenta = "tienda@gmail";
            revisar("ajustes de correo: correo malo se rechaza", lanzaCon(IllegalArgumentException.class, "Revise el correo",
                    () -> { try { a.guardar(bd); } catch (java.sql.SQLException e) { throw new RuntimeException(e); } }));
            a.cuenta = "tienda@gmail.com";
            a.clave = "abcd abcd abcd abcd";
            a.guardar(bd);
            rutas.logica.AjustesCorreo b = rutas.logica.AjustesCorreo.leer(bd);
            revisar("ajustes de correo se guardan (clave sin espacios)", b.cuenta.equals("tienda@gmail.com")
                    && b.clave.equals("abcdabcdabcdabcd") && b.listo());
        }
    }
}
