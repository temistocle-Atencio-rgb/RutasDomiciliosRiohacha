package rutas;

import static rutas.Pruebas.revisar;

import rutas.logica.ImportadorProductos;
import rutas.modelo.Unidad;

/** Pruebas de la validacion de datos (formulario de producto, importador y exportador). */
public class PruebasValidacion {

    static void correr() throws Exception {
        System.out.println("--- Validacion de datos ---");
        probarCantidades();
        probarPrecioCero();
        probarCsvFormulas();
        probarTodoONada();
        probarFormatoCodigo();
        probarMayusculas();
        probarTextos();
        probarMensajes();
        probarFotoLocal();
    }

    static java.io.File imagen(String formato, String nombre) throws Exception {
        java.io.File d = java.nio.file.Files.createTempDirectory("domiruta_foto").toFile();
        java.io.File f = new java.io.File(d, nombre);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(img, formato, f);
        return f;
    }

    /** H9 caso 19: el tipo de la foto se decide por su contenido. */
    private static void probarFotoLocal() throws Exception {
        java.io.File gif = imagen("gif", "foto.png");
        revisar("H9 caso 19: un GIF llamado .png se rechaza", lanza("no es una imagen JPG o PNG", () -> leer(gif)));
        java.io.File txt = new java.io.File(gif.getParentFile(), "texto.png");
        java.nio.file.Files.write(txt.toPath(), "hola".getBytes());
        revisar("H9 caso 18: un texto llamado .png se rechaza", lanza("no es una imagen", () -> leer(txt)));
        java.io.File png = imagen("png", "de-verdad.jpg");
        revisar("H9: un PNG de verdad pasa aunque se llame .jpg", noLanza(() -> leer(png)));
        revisar("H9: un JPG de verdad pasa", noLanza(() -> {
            try {
                leer(imagen("jpg", "x.jpg"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
        java.io.File grande = new java.io.File(gif.getParentFile(), "grande.png");
        java.nio.file.Files.write(grande.toPath(), new byte[1024 * 1024 + 1]);
        revisar("H9: más de 1 MB se rechaza", lanza("máximo es 1 MB", () -> leer(grande)));
    }

    static void leer(java.io.File f) {
        try {
            rutas.vista.PruebaAcceso.leerImagenLocal(f);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** H6: al usuario no le llegan los detalles tecnicos (quedan en errores.log). */
    private static void probarMensajes() throws Exception {
        java.sql.SQLException sql = new java.sql.SQLException("[SQLITE_CONSTRAINT_UNIQUE] UNIQUE constraint failed: productos.codigo");
        String m = rutas.logica.Ubicaciones.paraUsuario(sql, "No se pudo guardar el producto");
        revisar("H6: el mensaje de la base no muestra el detalle técnico: " + m, !m.contains("SQLITE") && !m.contains("constraint")
                && m.startsWith("No se pudo guardar el producto") && m.contains("errores.log"));
        String a = rutas.logica.Ubicaciones.paraUsuario(new java.nio.file.AccessDeniedException("C:\\x\\p.csv"), "No se pudo escribir p.csv");
        revisar("H6: un archivo bloqueado dice qué hacer, sin la ruta interna: " + a, !a.contains("C:\\x") && a.contains("abierto"));
        try (rutas.logica.BaseTienda bd = PruebasCaja.baseTemporal()) {
            rutas.logica.Catalogo cat = new rutas.logica.Catalogo(bd);
            bd.close(); // la base se cierra: guardar falla con un error de SQLite
            String err = null;
            try {
                importar(cat, "nombre;precio_venta\nPan;500\n");
            } catch (java.io.IOException e) {
                err = e.getMessage();
            }
            revisar("H6: si la base falla al importar, el mensaje no trae el texto de SQLite: " + err,
                    err != null && !err.toLowerCase().contains("sqlite") && !err.contains("closed"));
        }
    }

    static String crearConNombre(rutas.logica.Catalogo cat, String nombre) {
        try {
            cat.crear(new rutas.modelo.Producto(0, null, nombre, Unidad.UNIDAD, 1000, 0, 0, 0, null, true));
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (java.sql.SQLException e) {
            return "SQL " + e.getMessage();
        }
    }

    /** H4: largos maximos, espacios dobles, emojis y caracteres invisibles (caso 1 y 2). */
    private static void probarTextos() throws Exception {
        try (rutas.logica.BaseTienda bd = PruebasCaja.baseTemporal()) {
            rutas.logica.Catalogo cat = new rutas.logica.Catalogo(bd);
            String e1 = crearConNombre(cat, "x".repeat(10_000));
            revisar("H4 caso 1: nombre de 10.000 letras se rechaza: " + e1, e1 != null && e1.contains("máximo es 120"));
            revisar("H4: 120 letras sí entran", crearConNombre(cat, "y".repeat(120)) == null);
            revisar("H4: espacios dobles se quitan", crearConNombre(cat, "  Arroz    Diana  ") == null
                    && cat.buscarNombre("arroz diana", 1).get(0).getNombre().equals("Arroz Diana"));
            revisar("H4 caso 2: «' OR 1=1 --» se guarda como texto (no rompe nada)", crearConNombre(cat, "' OR 1=1 --") == null
                    && cat.buscarNombre("' or 1=1", 1).size() == 1);
            String emoji = crearConNombre(cat, "Torta \uD83C\uDF82");
            revisar("H4: emojis en el nombre se rechazan: " + emoji, emoji != null && emoji.contains("emojis"));
            revisar("H4: también el corazón ❤ y el invisible de los emojis",
                    crearConNombre(cat, "Amor ❤️") != null && crearConNombre(cat, "Uno\u200DDos") != null);
            revisar("H4: ° ® ™ ½ y tildes sí se permiten", crearConNombre(cat, "Aguardiente 29° Néctar® ½ lt™") == null);
            revisar("H4: caracteres de control se rechazan", crearConNombre(cat, "Pa\u0007n") != null);
            rutas.modelo.FichaProducto f = new rutas.modelo.FichaProducto();
            f.descripcion = "d".repeat(501);
            revisar("H4: descripción de 501 se rechaza", lanza("máximo es 500", () -> f.validar(1000)));
            f.descripcion = "línea uno\nlínea dos";
            f.garantia = "g".repeat(301);
            revisar("H4: garantía de 301 se rechaza (y la descripción con saltos de línea sí entra)",
                    lanza("garantía", () -> f.validar(1000)));
            f.garantia = "";
            f.caracterizar = true;
            f.caracteristicas.add(new String[]{"c".repeat(41), "rojo"});
            revisar("H4: nombre de característica de 41 se rechaza", lanza("máximo es 40", () -> f.validar(1000)));
            f.caracteristicas.set(0, new String[]{"Color", "v".repeat(121)});
            revisar("H4: valor de característica de 121 se rechaza", lanza("máximo es 120", () -> f.validar(1000)));
            rutas.modelo.FichaProducto antes = new rutas.modelo.FichaProducto();
            antes.caracteristicas.add(new String[]{"Color", "v".repeat(121)});
            revisar("H4: una característica vieja larga que no se tocó no impide guardar", noLanza(() -> f.validar(1000, antes)));
            revisar("H4: limpiar quita espacios dobles y de los lados",
                    rutas.logica.Textos.limpiar("  a   b \n c ").equals("a b c")
                    && rutas.logica.Textos.limpiarLineas(" a   b \n\n\n\n c ").equals("a b\n\nc"));
            // un nombre viejo de mas de 120 (de antes de la regla) no impide cambiarle el precio
            long id = bd.insertarProducto(new rutas.modelo.Producto(0, "LARGO", "z".repeat(150), Unidad.UNIDAD, 900, 0, 0, 0,
                    null, true)).getId();
            cat.recargar();
            revisar("H4: un nombre viejo de 150 sin tocar no impide editar el precio", noLanza(() -> {
                try {
                    cat.editar(new rutas.modelo.Producto(id, "LARGO", "z".repeat(150), Unidad.UNIDAD, 950, 0, 0, 0, null, true));
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        java.util.List<rutas.modelo.Cliente> hay = new java.util.ArrayList<>();
        revisar("H4: nota del cliente de 301 y dirección de 151 se rechazan",
                lanza("La nota", () -> rutas.vista.PruebaAcceso.armarClienteCon(hay, null, "Ana Ruiz", "Calle 1", "n".repeat(301)))
                && lanza("La dirección", () -> rutas.vista.PruebaAcceso.armarClienteCon(hay, null, "Ana Ruiz", "d".repeat(151), "")));
        rutas.modelo.Cliente viejo = new rutas.modelo.Cliente("Ana Ruiz", "", "d".repeat(151), "", 0, 0);
        revisar("H4: una dirección vieja larga sin tocar no impide editar el cliente",
                noLanza(() -> rutas.vista.PruebaAcceso.armarClienteCon(java.util.List.of(viejo), viejo, "Ana Ruiz",
                        "d".repeat(151), "portón verde")));
    }

    /** H10: los codigos se guardan y se buscan en mayusculas (caso 21). */
    private static void probarMayusculas() throws Exception {
        java.io.File archivo;
        try (rutas.logica.BaseTienda bd = PruebasCaja.baseTemporal()) {
            archivo = bd.getArchivo();
            rutas.logica.Catalogo cat = new rutas.logica.Catalogo(bd);
            revisar("H10: «abc123» se guarda como ABC123", crearConCodigo(cat, "abc123") == null
                    && bd.buscarPorCodigoEnBase("ABC123").getCodigo().equals("ABC123"));
            String e21 = crearConCodigo(cat, "ABC123");
            revisar("H10 caso 21: ABC123 después de abc123 se rechaza por repetido: " + e21, e21 != null && e21.contains("ya es de"));
            revisar("H10: el lector en minúsculas encuentra el producto", cat.buscarCodigo("abc123") != null
                    && bd.buscarPorCodigoEnBase("aBc123") != null);
            // bases de antes: codigos en minusculas, uno de ellos chocando con otro en mayusculas
            try (java.sql.Connection con = java.sql.DriverManager.getConnection("jdbc:sqlite:" + archivo.getAbsolutePath());
                 java.sql.Statement st = con.createStatement()) {
                st.execute("DROP INDEX IF EXISTS idx_codigo_mayus");
                st.execute("INSERT INTO productos(codigo, nombre, clave_nombre, unidad, precio_venta, precio_compra,"
                        + " actualizado) VALUES('xy-1','A','a','UNIDAD',100,0,'2026-01-01T00:00'),"
                        + " ('zz-9','B','b','UNIDAD',100,0,'2026-01-01T00:00'), ('ZZ-9','C','c','UNIDAD',100,0,'2026-01-01T00:00')");
            }
        }
        try (rutas.logica.BaseTienda bd = new rutas.logica.BaseTienda(archivo, 1000)) {
            revisar("H10: al abrir una base vieja los códigos pasan a mayúsculas",
                    bd.buscarPorCodigoEnBase("xy-1").getCodigo().equals("XY-1"));
            revisar("H10: los que chocan se dejan como estaban y se avisan: " + bd.codigosRepetidos(),
                    bd.codigosRepetidos().equals(java.util.List.of("ZZ-9")));
        }
    }

    /** Crea un producto con ese codigo (null si se guardo; el mensaje si se rechazo). */
    static String crearConCodigo(rutas.logica.Catalogo cat, String codigo) {
        try {
            cat.crear(new rutas.modelo.Producto(0, codigo, "P " + codigo, Unidad.UNIDAD, 1000, 0, 0, 0, null, true));
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (java.sql.SQLException e) {
            return "SQL " + e.getMessage();
        }
    }

    /** H3: formato del codigo (casos 5, 7 y 8). */
    private static void probarFormatoCodigo() throws Exception {
        try (rutas.logica.BaseTienda bd = PruebasCaja.baseTemporal()) {
            rutas.logica.Catalogo cat = new rutas.logica.Catalogo(bd);
            String e5 = crearConCodigo(cat, "7,7E+12");
            revisar("H3 caso 5: «7,7E+12» en el formulario se rechaza explicando lo de Excel: " + e5,
                    e5 != null && e5.contains("notación científica"));
            String e7 = crearConCodigo(cat, "234567890'");
            revisar("H3 caso 7: código con apóstrofe se rechaza: " + e7, e7 != null && e7.contains("solo letras, números"));
            revisar("H3: con espacio en medio o de más de 40 se rechaza", crearConCodigo(cat, "770 123") != null
                    && crearConCodigo(cat, "A".repeat(41)) != null);
            revisar("H3: códigos propios con letras y guion sí entran", crearConCodigo(cat, "XY-CH-001") == null);
            revisar("H3 caso 4: ceros a la izquierda se conservan", crearConCodigo(cat, "0012345678905") == null
                    && cat.buscarCodigo("0012345678905") != null);
            revisar("H3: dígito de control de EAN-13, EAN-8 y UPC-A", rutas.logica.Catalogo.digitoControlValido("7702004003508")
                    && rutas.logica.Catalogo.digitoControlValido("96385074")
                    && rutas.logica.Catalogo.digitoControlValido("036000291452"));
            revisar("H3 caso 8: EAN-13 con el dígito malo solo da aviso (se guarda)",
                    !rutas.logica.Catalogo.digitoControlValido("7702004003500") && crearConCodigo(cat, "7702004003500") == null);
            revisar("H3: un código propio no se revisa como EAN", rutas.logica.Catalogo.digitoControlValido("ABC123")
                    && rutas.logica.Catalogo.digitoControlValido("12345"));
            ImportadorProductos.Resultado r = importar(cat, "nombre;codigo;precio_venta\nCafé;7702004003517;5000\n");
            revisar("H3: el importador guarda el EAN con dígito malo y lo deja en avisos: " + r.avisos,
                    r.errores.isEmpty() && r.creados == 1 && r.avisos.size() == 1 && r.avisos.get(0).contains("7702004003517"));
            // un codigo viejo que no cumple el formato nuevo no impide editar lo demas
            long id = bd.insertarProducto(new rutas.modelo.Producto(0, "VIEJO 1", "Viejo", Unidad.UNIDAD, 900, 0, 0, 0, null, true)).getId();
            cat.recargar();
            revisar("H3: un código viejo con espacio no impide cambiar el precio", noLanza(() -> {
                try {
                    cat.editar(new rutas.modelo.Producto(id, "VIEJO 1", "Viejo", Unidad.UNIDAD, 950, 0, 0, 0, null, true));
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
    }

    /** H5 caso 20: 3 filas con la del medio mala: no se guarda ninguna, ni la categoria nueva. */
    private static void probarTodoONada() throws Exception {
        try (rutas.logica.BaseTienda bd = PruebasCaja.baseTemporal()) {
            rutas.logica.Catalogo cat = new rutas.logica.Catalogo(bd);
            cat.crear(new rutas.modelo.Producto(0, "500", "Sal", Unidad.UNIDAD, 1000, 0, 0, 0, null, true));
            ImportadorProductos.Resultado r = importar(cat, "nombre;codigo;precio_venta;categoria\n"
                    + "Leche;501;3000;Lácteos\n;502;2000;\nSal refinada;500;1200;\n");
            revisar("H5 caso 20: la fila mala se informa y no se guarda ninguna: " + r.errores, r.errores.size() == 1
                    && r.errores.get(0).startsWith("Fila 3") && r.creados == 0 && r.actualizados == 0
                    && cat.buscarCodigo("501") == null && bd.buscarPorCodigoEnBase("501") == null);
            revisar("H5: lo que ya existía queda como estaba (base y árboles) y la categoría nueva no se crea",
                    cat.buscarCodigo("500").getNombre().equals("Sal") && bd.buscarPorCodigoEnBase("500").getPrecioVenta() == 1000
                    && bd.listarCategorias(true).isEmpty());
            ImportadorProductos.Resultado bien = importar(cat, "nombre;codigo;precio_venta\nLeche;501;3000\nPan;502;500\n");
            revisar("H5: un archivo sin errores sí entra completo", bien.errores.isEmpty() && bien.creados == 2
                    && cat.buscarCodigo("502") != null);
        }
    }

    /** H1: el CSV exportado no deja formulas vivas (caso 3), y la lista va y vuelve igual. */
    private static void probarCsvFormulas() throws Exception {
        try (rutas.logica.BaseTienda bd = PruebasCaja.baseTemporal()) {
            rutas.logica.Catalogo cat = new rutas.logica.Catalogo(bd);
            cat.crear(new rutas.modelo.Producto(0, "77", "=HYPERLINK(\"http://x\",\"y\")", Unidad.UNIDAD, 1000, 500,
                    0, 0, null, true));
            cat.crear(new rutas.modelo.Producto(0, "78", "@SUMA y -resta", Unidad.UNIDAD, 2000, 0, 0, 0, null, true));
            cat.crear(new rutas.modelo.Producto(0, "79", "Arroz; libra", Unidad.UNIDAD, 3000, 0, 0, 0, null, true));
            String csv = rutas.logica.ListadoProductos.aCsv(cat.todosConInactivos(), new java.util.HashMap<>());
            revisar("H1 caso 3: =HYPERLINK sale como texto (con ' delante y entre comillas)",
                    csv.contains("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\";") && !csv.contains(";=HYPERLINK")
                    && !csv.contains("\n=HYPERLINK"));
            revisar("H1: @ también se protege y el ; queda dentro de comillas",
                    csv.contains("\"'@SUMA y -resta\"") && csv.contains("\"Arroz; libra\""));
            ImportadorProductos.Resultado r = importar(cat, csv);
            revisar("H1: volver a importar quita la comilla (el nombre queda igual): " + r.errores, r.errores.isEmpty()
                    && cat.buscarCodigo("77").getNombre().equals("=HYPERLINK(\"http://x\",\"y\")")
                    && cat.buscarCodigo("78").getNombre().equals("@SUMA y -resta")
                    && cat.buscarCodigo("79").getNombre().equals("Arroz; libra"));
        }
    }

    /** Escribe un CSV temporal y lo importa. */
    static ImportadorProductos.Resultado importar(rutas.logica.Catalogo cat, String contenido) throws Exception {
        java.io.File f = new java.io.File(java.nio.file.Files.createTempDirectory("domiruta_val").toFile(), "p.csv");
        java.nio.file.Files.write(f.toPath(), contenido.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ImportadorProductos.importar(f, cat);
    }

    /** H2: precio de venta $0 se bloquea (decision 7); costo mayor que la venta se deja. */
    private static void probarPrecioCero() throws Exception {
        try (rutas.logica.BaseTienda bd = PruebasCaja.baseTemporal()) {
            rutas.logica.Catalogo cat = new rutas.logica.Catalogo(bd);
            revisar("H2: precio de venta $0 se bloquea", lanza("mayor que $0", () -> {
                try {
                    cat.crear(new rutas.modelo.Producto(0, "1", "Bolsa", Unidad.UNIDAD, 0, 0, 0, 0, null, true));
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }));
            revisar("H2: costo mayor que la venta sí se guarda (solo es aviso)", noLanza(() -> {
                try {
                    cat.crear(new rutas.modelo.Producto(0, "2", "Pan", Unidad.UNIDAD, 1000, 1200, 0, 0, null, true));
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }));
            ImportadorProductos.Resultado r = importar(cat, "nombre;codigo;unidad;precio_venta;stock\n"
                    + "Leche;10;und;3000;-2\nQueso;11;und;9000;1,5\nArroz;12;kg;2500;0.500\n");
            revisar("H2: el importador rechaza stock negativo, decimales en und y «0.500»: " + r.errores,
                    r.errores.size() == 3 && r.errores.get(0).contains("negativo") && r.errores.get(1).contains("enteras")
                    && r.errores.get(2).contains("0,5"));
        }
    }

    static boolean lanza(String pedazo, Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return e.getMessage() != null && e.getMessage().contains(pedazo);
        }
    }

    /** H2: cantidades y precios escritos. Casos 11, 13, 14, 15 y 16 de la revision. */
    private static void probarCantidades() {
        revisar("H2 caso 13: «1.200» en cantidad es mil doscientos", ImportadorProductos.leerCantidad("1.200") == 1_200_000);
        revisar("H2 caso 14: «0.500» se rechaza y pide escribir 0,5",
                lanza("«0,5»", () -> ImportadorProductos.leerCantidad("0.500")));
        revisar("H2: «0,5» y «0.5» sí son medio", ImportadorProductos.leerCantidad("0,5") == 500
                && ImportadorProductos.leerCantidad("0.5") == 500);
        revisar("H2 caso 16: notación científica «7,7E+12» se rechaza",
                lanza("no válida", () -> ImportadorProductos.leerCantidad("7,7E+12"))
                && lanza("no válida", () -> ImportadorProductos.leerCantidad("1e3")));
        revisar("H2: más de 3 decimales se rechaza (no se redondea a escondidas)",
                lanza("3 decimales", () -> ImportadorProductos.leerCantidad("1,2345")));
        revisar("H2: cantidad máxima 999.999", ImportadorProductos.leerCantidad("999.999") == 999_999_000L
                && lanza("999.999", () -> ImportadorProductos.leerCantidad("1000000")));
        revisar("H2: letras y emojis en la cantidad se rechazan",
                lanza("no válida", () -> ImportadorProductos.leerCantidad("dos"))
                && lanza("no válida", () -> ImportadorProductos.leerCantidad("2😀")));
        revisar("H2 caso 15: decimales en un producto por unidad se rechazan al escribir el stock",
                lanza("unidades enteras", () -> ImportadorProductos.revisarCantidadEscrita(1500, Unidad.UNIDAD, "El stock")));
        revisar("H2: en kilos sí van decimales", noLanza(() -> ImportadorProductos.revisarCantidadEscrita(1500, Unidad.KILO, "x")));
        revisar("H2: stock negativo escrito se rechaza",
                lanza("negativo", () -> ImportadorProductos.revisarCantidadEscrita(-5000, Unidad.UNIDAD, "El stock")));
        revisar("H2 caso 11: precio de 20 cifras da un mensaje claro (no «For input string»)",
                lanza("$99.999.999", () -> ImportadorProductos.leerPesos("99999999999999999999")));
        revisar("H2: el precio máximo es 99.999.999", ImportadorProductos.leerPesos("99.999.999") == 99_999_999L
                && lanza("$99.999.999", () -> ImportadorProductos.leerPesos("100.000.000")));
        revisar("H2 caso 9: precio negativo se rechaza", lanza("no válido", () -> ImportadorProductos.leerPesos("-500")));
    }

    static boolean noLanza(Runnable r) {
        try {
            r.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
