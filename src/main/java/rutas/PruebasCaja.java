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
            revisar("el cajero no ve precio de compra", !cajero.puede(Permiso.VER_COMPRA) && !cajero.esAdmin());
            revisar("la admin si", s.puede(Permiso.VER_COMPRA) && s.puede(Permiso.ADMIN_USUARIOS));
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
            Producto chicle = cat.crear(new Producto(0, null, "Chicle", Unidad.UNIDAD, 200, 100, 0, 0,
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
            revisar("vender sin existencias deja el stock en negativo", bd.buscarProductoPorId(chicle.getId()).getStock() == -1000
                    && cat.buscarPorId(chicle.getId()).getStock() == -1000);

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
            File carpeta = java.nio.file.Files.createTempDirectory("domiruta_export").toFile();
            File archivo = new File(carpeta, "productos.csv");
            java.nio.file.Files.write(archivo.toPath(), csv.getBytes("UTF-8"));
            try (BaseTienda otra = baseTemporal()) {
                otra.crearCategoria("Granos", null, 0);
                Catalogo cat2 = new Catalogo(otra);
                ImportadorProductos.Resultado r = ImportadorProductos.importar(archivo, cat2);
                Producto az = cat2.buscarNombre("azucar", 1).get(0);
                revisar("exportar y volver a importar da los mismos productos: " + r.creados + " " + r.errores,
                        r.creados == 4 && r.errores.isEmpty() && az.getStock() == 2000 && az.getUnidad() == Unidad.LIBRA
                        && az.getPrecioVenta() == 2200 && az.getCategoriaId() != null
                        && cat2.buscarCodigo("7703").getStock() == -3000);
            }
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
}
