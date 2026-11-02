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
 * nombre; codigo; unidad; precio_venta; precio_compra; stock; minimo; categoria.
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
        Map<String, Long> categorias = new HashMap<>();
        try {
            for (rutas.modelo.Categoria c : cat.base().listarCategorias(true)) {
                categorias.put(Geo.normalizar(c.getNombre()), c.getId());
            }
        } catch (SQLException e) {
            throw new IOException("No se pudieron leer las categorías: " + e.getMessage(), e);
        }
        try {
            cat.base().enTransaccion(() -> {
                for (int i = 1; i < lineas.size(); i++) {
                    if (lineas.get(i).isBlank()) {
                        continue;
                    }
                    try {
                        importarFila(partir(lineas.get(i), sep), col, cat, r, categorias);
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

    private static void importarFila(List<String> f, Map<String, Integer> col, Catalogo cat, Resultado r,
            Map<String, Long> categorias) throws SQLException {
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
        // lo que viene escrito en el archivo y cambia algo; una lista exportada con un stock negativo
        // (por ventas) se puede volver a importar igual
        if (!celda(f, col, "stock").isEmpty() && (viejo == null || stock != viejo.getStock())) {
            revisarCantidadEscrita(stock, unidad, "El stock");
        }
        if (!celda(f, col, "minimo").isEmpty() && (viejo == null || minimo != viejo.getMinimo())) {
            revisarCantidadEscrita(minimo, unidad, "El mínimo");
        }

        String textoCategoria = celda(f, col, "categoria");
        Long categoriaId = viejo == null ? null : viejo.getCategoriaId();
        if (!textoCategoria.isEmpty()) { // si no existe la categoria, se crea
            String clave = Geo.normalizar(textoCategoria);
            categoriaId = categorias.get(clave);
            if (categoriaId == null) {
                categoriaId = cat.base().crearCategoria(textoCategoria, null, categorias.size()).getId();
                categorias.put(clave, categoriaId);
            }
        }
        if (viejo == null) {
            cat.crear(new Producto(0, codigo, nombre, unidad, venta, compra, stock, minimo, null, true, categoriaId));
            r.creados++;
        } else {
            cat.editar(new Producto(viejo.getId(), codigo, nombre, unidad, venta, compra, stock, minimo,
                    viejo.getFoto(), true, categoriaId, viejo.isManejaInventario()));
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
        // la comilla simple que pone el exportador delante de = + - @ se quita (asi la lista va y vuelve igual)
        return i == null || i >= f.size() ? "" : ListadoProductos.sinProteccion(f.get(i).trim());
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
        String cifras = s.replace(".", "").replaceFirst("^0+(?=\\d)", "");
        if (cifras.length() > 8 || Long.parseLong(cifras) > PRECIO_MAXIMO) { // antes de convertir: sin desbordes
            throw new IllegalArgumentException("El valor máximo es $99.999.999");
        }
        return Long.parseLong(cifras);
    }

    /** Lo mas alto que se puede escribir como precio o valor (pesos). */
    public static final long PRECIO_MAXIMO = 99_999_999L;
    /** Lo mas alto que se puede escribir como cantidad: 999.999 (en milesimas). */
    public static final long CANTIDAD_MAXIMA = 999_999_000L;

    /**
     * Cantidad en milesimas: "1,5" y "1.5" son 1500; "3" es 3000.
     * Un punto seguido de 3 cifras es de miles, como en los precios: "1.200" son 1200.
     * Se rechaza (en vez de adivinar): "0.500" (¿medio o quinientos?), la notacion cientifica de
     * Excel ("7,7E+12"), mas de 3 decimales y mas de 999.999.
     */
    public static long leerCantidad(String texto) {
        String s = texto == null ? "" : texto.replace(" ", "");
        if (!s.matches("-?[0-9.,]{1,16}")) { // lista blanca: solo cifras, punto y coma (y el signo)
            throw new IllegalArgumentException("Cantidad no válida: " + (texto == null ? "" : texto.trim()));
        }
        String sinSigno = s.startsWith("-") ? s.substring(1) : s;
        if (sinSigno.matches("0+\\.\\d{3}")) {
            throw new IllegalArgumentException("Escriba «" + sinSigno.replaceFirst("^0+", "0").replace('.', ',')
                    .replaceFirst(",?0+$", "").replaceFirst(",$", "") + "» con coma: «0.500» se confunde con quinientos");
        }
        if (sinSigno.matches("\\d{1,3}(\\.\\d{3})+(,\\d+)?")) {
            s = s.replace(".", ""); // "1.200" o "1.200,5": los puntos separan miles
        }
        s = s.replace(',', '.');
        BigDecimal b;
        try {
            b = new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cantidad no válida: " + texto.trim());
        }
        if (b.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("La cantidad lleva máximo 3 decimales");
        }
        long m = b.movePointRight(3).setScale(0, RoundingMode.UNNECESSARY).longValue();
        if (Math.abs(m) > CANTIDAD_MAXIMA) {
            throw new IllegalArgumentException("La cantidad máxima es 999.999");
        }
        return m;
    }

    /**
     * Revisa una cantidad que el usuario escribio para guardar (stock, minimo): no negativa y, si
     * el producto va por unidades, sin decimales. Las ventas no pasan por aqui (pueden dejar el
     * stock negativo).
     */
    public static void revisarCantidadEscrita(long milesimas, Unidad unidad, String que) {
        if (milesimas < 0) {
            throw new IllegalArgumentException(que + " no puede ser negativo");
        }
        if (unidad == Unidad.UNIDAD && milesimas % 1000 != 0) {
            throw new IllegalArgumentException(que + " va en unidades enteras (el producto no es a granel)");
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

    /**
     * Lee un CSV en filas: el separador (; o ,) se escoge una sola vez con la fila de titulos y se
     * respetan las comillas ("Calle 15 # 7-20, barrio Centro" es un solo campo).
     */
    public static List<String[]> leerCsv(File archivo) throws IOException {
        List<String> lineas = leerLineas(archivo);
        List<String[]> filas = new ArrayList<>();
        if (lineas.isEmpty()) {
            return filas;
        }
        char sep = lineas.get(0).indexOf(';') >= 0 ? ';' : ',';
        for (String l : lineas) {
            filas.add(partir(l, sep).toArray(new String[0]));
        }
        return filas;
    }

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
