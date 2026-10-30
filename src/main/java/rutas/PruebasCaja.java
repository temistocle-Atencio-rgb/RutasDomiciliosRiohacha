package rutas;

import static rutas.Pruebas.revisar;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import rutas.logica.BaseTienda;
import rutas.logica.Catalogo;
import rutas.logica.ImportadorProductos;
import rutas.logica.Ingreso;
import rutas.logica.Permiso;
import rutas.logica.Seguridad;
import rutas.logica.Sesion;
import rutas.modelo.Rol;
import rutas.modelo.Usuario;
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
}
