package rutas.logica;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import rutas.modelo.Geo;
import rutas.modelo.Producto;
import rutas.modelo.Unidad;

/**
 * Lee una lista de productos en CSV (lo que guarda Excel con "Guardar como CSV").
 *
 * Columnas (por el nombre del encabezado, en cualquier orden):
 * nombre; codigo; unidad; precio_venta; precio_compra; stock; minimo.
 * Obligatorias: nombre y precio_venta. Si el codigo ya existe, se actualiza ese producto.
 * Las filas con error no detienen a las demas: al final se dice cuales fallaron y por que.
 */
public final class ImportadorProductos {

    private static final String[] OBLIGATORIAS = {"nombre", "precio_venta"};

    private ImportadorProductos() {
    }

    /** Lo que paso al importar. */
    public static class Resultado {
        public int creados;
        public int actualizados;
        public final List<String> errores = new ArrayList<>();
    }

    public static Resultado importar(File csv, Catalogo cat) throws IOException {
        List<String> lineas = leerLineas(csv);
        if (lineas.isEmpty()) {
            throw new IllegalArgumentException("El archivo está vacío");
        }
        char sep = lineas.get(0).indexOf(';') >= 0 ? ';' : ',';
        Map<String, Integer> col = new HashMap<>();
        List<String> encabezado = partir(lineas.get(0), sep);
        for (int i = 0; i < encabezado.size(); i++) {
            col.put(Geo.normalizar(encabezado.get(i)).replace(' ', '_'), i);
        }
        List<String> faltan = new ArrayList<>();
        for (String o : OBLIGATORIAS) {
            if (!col.containsKey(o)) {
                faltan.add(o);
            }
        }
        if (!faltan.isEmpty()) {
            throw new IllegalArgumentException("Faltan columnas: " + String.join(", ", faltan));
        }

        Resultado r = new Resultado();
        try {
            cat.base().enTransaccion(() -> {
                for (int i = 1; i < lineas.size(); i++) {
                    if (lineas.get(i).isBlank()) {
                        continue;
                    }
                    try {
                        importarFila(partir(lineas.get(i), sep), col, cat, r);
                    } catch (IllegalArgumentException e) {
                        r.errores.add("Fila " + (i + 1) + ": " + e.getMessage());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            try {
                cat.recargar(); // la base se deshizo: los arboles vuelven a quedar como la base
            } catch (SQLException e2) {
                e.addSuppressed(e2);
            }
            throw new IOException("No se pudo guardar la lista: " + e.getMessage(), e);
        }
        return r;
    }

    private static void importarFila(List<String> f, Map<String, Integer> col, Catalogo cat, Resultado r)
            throws SQLException {
        String nombre = celda(f, col, "nombre");
        if (nombre.isEmpty()) {
            throw new IllegalArgumentException("Falta el nombre");
        }
        String codigo = celda(f, col, "codigo").replace(" ", "");
        if (codigo.toUpperCase().matches(".*\\d[.,]?\\d*E\\+\\d+")) {
            throw new IllegalArgumentException("Excel cambió el código; formatee la columna como texto");
        }
        Producto viejo = codigo.isEmpty() ? null : cat.buscarCodigo(codigo);
        String textoPrecio = celda(f, col, "precio_venta");
        if (textoPrecio.isEmpty() && viejo == null) {
            throw new IllegalArgumentException("Falta el precio de venta");
        }
        Unidad unidad = leerUnidad(celda(f, col, "unidad"), viejo == null ? Unidad.UNIDAD : viejo.getUnidad());
        long venta = textoPrecio.isEmpty() ? viejo.getPrecioVenta() : leerPesos(textoPrecio);
        long compra = valor(celda(f, col, "precio_compra"), viejo == null ? 0 : viejo.getPrecioCompra(), true);
        long stock = valor(celda(f, col, "stock"), viejo == null ? 0 : viejo.getStock(), false);
        long minimo = valor(celda(f, col, "minimo"), viejo == null ? 0 : viejo.getMinimo(), false);

        if (viejo == null) {
            cat.crear(new Producto(0, codigo, nombre, unidad, venta, compra, stock, minimo, null, true));
            r.creados++;
        } else {
            cat.editar(new Producto(viejo.getId(), codigo, nombre, unidad, venta, compra, stock, minimo,
                    viejo.getFoto(), true));
            r.actualizados++;
        }
    }

    private static long valor(String texto, long siFalta, boolean esPlata) {
        if (texto.isEmpty()) {
            return siFalta;
        }
        return esPlata ? leerPesos(texto) : leerCantidad(texto);
    }

    private static String celda(List<String> f, Map<String, Integer> col, String nombre) {
        Integer i = col.get(nombre);
        return i == null || i >= f.size() ? "" : f.get(i).trim();
    }

    // ---------------- numeros escritos a la colombiana ----------------

    /**
     * Pesos sin centavos: "$2.500", "2.500,00", "2,500" y "2500" son 2500.
     * "2.500,50" es un error (no hay centavos).
     */
    public static long leerPesos(String texto) {
        String s = texto.replace("$", "").replace(" ", "").trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Falta el precio");
        }
        int coma = s.lastIndexOf(',');
        if (coma >= 0) {
            String despues = s.substring(coma + 1);
            if (despues.matches("0{1,2}")) {
                s = s.substring(0, coma); // ",00" no cambia nada
            } else if (despues.matches("\\d{3}") && s.indexOf('.') < 0) {
                s = s.replace(",", ""); // la coma separa miles: 2,500
            } else {
                throw new IllegalArgumentException("Los precios van sin centavos");
            }
        }
        if (!s.matches("\\d{1,3}(\\.\\d{3})*|\\d+")) {
            throw new IllegalArgumentException(s.matches("[\\d.,]+") ? "Los precios van sin centavos"
                    : "Precio no válido: " + texto.trim());
        }
        return Long.parseLong(s.replace(".", ""));
    }

    /**
     * Cantidad en milesimas: "1,5" y "1.5" son 1500; "3" es 3000.
     * Un punto seguido de 3 cifras es de miles, como en los precios: "1.200" son 1200.
     */
    public static long leerCantidad(String texto) {
        String s = texto.replace(" ", "");
        if (s.matches("\\d{1,3}(\\.\\d{3})+(,\\d+)?")) {
            s = s.replace(".", ""); // "1.200" o "1.200,5": los puntos separan miles
        }
        s = s.replace(',', '.');
        try {
            return new BigDecimal(s).multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Cantidad no válida: " + texto.trim());
        }
    }

    private static Unidad leerUnidad(String texto, Unidad siFalta) {
        switch (Geo.normalizar(texto).replace(".", "")) {
            case "":
                return siFalta;
            case "u":
            case "und":
            case "unidad":
            case "unidades":
                return Unidad.UNIDAD;
            case "lb":
            case "libra":
            case "libras":
                return Unidad.LIBRA;
            case "kg":
            case "kilo":
            case "kilos":
            case "kilogramo":
                return Unidad.KILO;
            default:
                throw new IllegalArgumentException("Unidad no reconocida: " + texto.trim() + " (use und, lb o kg)");
        }
    }

    // ---------------- leer el archivo ----------------

    /** Lee en UTF-8; si el archivo viene del Excel de Windows (otras letras), lo lee en windows-1252. */
    private static List<String> leerLineas(File archivo) throws IOException {
        byte[] bytes = Files.readAllBytes(archivo.toPath());
        String texto;
        try {
            texto = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            texto = new String(bytes, Charset.forName("windows-1252"));
        }
        if (texto.startsWith("﻿")) {
            texto = texto.substring(1);
        }
        List<String> lineas = new ArrayList<>();
        for (String l : texto.split("\r\n|\n|\r", -1)) {
            lineas.add(l);
        }
        while (!lineas.isEmpty() && lineas.get(lineas.size() - 1).isBlank()) {
            lineas.remove(lineas.size() - 1);
        }
        return lineas;
    }

    /** Parte una linea respetando las comillas ("Queso; libra" es un solo campo). */
    static List<String> partir(String linea, char sep) {
        List<String> campos = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        boolean comillas = false;
        for (int i = 0; i < linea.length(); i++) {
            char c = linea.charAt(i);
            if (comillas) {
                if (c == '"' && i + 1 < linea.length() && linea.charAt(i + 1) == '"') {
                    actual.append('"');
                    i++;
                } else if (c == '"') {
                    comillas = false;
                } else {
                    actual.append(c);
                }
            } else if (c == '"') {
                comillas = true;
            } else if (c == sep) {
                campos.add(actual.toString());
                actual.setLength(0);
            } else {
                actual.append(c);
            }
        }
        campos.add(actual.toString());
        return campos;
    }
}
