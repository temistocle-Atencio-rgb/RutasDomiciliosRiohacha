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
