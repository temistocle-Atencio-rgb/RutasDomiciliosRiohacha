package rutas.logica;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import rutas.modelo.Geo;
import rutas.modelo.Producto;

/**
 * Lo que necesita la tabla de productos: filtrar, partir en paginas, los numeros de
 * pagina que se muestran abajo y exportar a un CSV que Excel abre y que se puede
 * volver a importar. No usa Swing.
 */
public final class ListadoProductos {

    /** Que productos se muestran. */
    public enum Mostrar {
        ACTIVOS("Activos"),
        BAJO_MINIMO("Bajo el mínimo"),
        SIN_EXISTENCIAS("Sin existencias"),
        DESACTIVADOS("Desactivados"),
        TODOS("Todos");

        private final String texto;

        Mostrar(String texto) {
            this.texto = texto;
        }

        @Override
        public String toString() {
            return texto;
        }
    }

    private ListadoProductos() {
    }

    /**
     * Filtra la lista (que ya viene ordenada por nombre).
     * El texto busca el codigo exacto o una parte del nombre, sin tildes ni mayusculas.
     */
    public static List<Producto> filtrar(List<Producto> todos, String texto, Long categoriaId, Mostrar mostrar) {
        String t = texto == null ? "" : texto.trim();
        String clave = Geo.normalizar(t);
        List<Producto> l = new ArrayList<>();
        for (Producto p : todos) {
            if (categoriaId != null && !categoriaId.equals(p.getCategoriaId())) {
                continue;
            }
            if (!t.isEmpty() && !t.equals(p.getCodigo()) && !p.getClaveNombre().contains(clave)) {
                continue;
            }
            if (cumple(p, mostrar)) {
                l.add(p);
            }
        }
        return l;
    }

    private static boolean cumple(Producto p, Mostrar m) {
        switch (m) {
            case ACTIVOS:
                return p.isActivo();
            case BAJO_MINIMO:
                return p.isActivo() && p.estaBajoMinimo();
            case SIN_EXISTENCIAS:
                return p.isActivo() && p.isManejaInventario() && p.getStock() <= 0;
            case DESACTIVADOS:
                return !p.isActivo();
            default:
                return true;
        }
    }

    /** Una pagina de la tabla. */
    public static class Pagina {
        private final List<Producto> productos;
        private final int numero;
        private final int totalPaginas;
        private final int total;
        private final int desde;

        Pagina(List<Producto> productos, int numero, int totalPaginas, int total, int desde) {
            this.productos = productos;
            this.numero = numero;
            this.totalPaginas = totalPaginas;
            this.total = total;
            this.desde = desde;
        }

        public List<Producto> getProductos() {
            return productos;
        }

        public int getNumero() {
            return numero;
        }

        public int getTotalPaginas() {
            return totalPaginas;
        }

        public int getTotal() {
            return total;
        }

        /** Numero (desde 1) del primer producto de la pagina; sirve para la columna Item. */
        public int getDesde() {
            return desde;
        }
    }

    /** Parte la lista en paginas; si la pagina pedida no existe, se usa la ultima (o la primera). */
    public static Pagina pagina(List<Producto> l, int porPagina, int numero) {
        int total = l.size();
        int paginas = Math.max(1, (total + porPagina - 1) / porPagina);
        int n = Math.max(1, Math.min(numero, paginas));
        int desde = (n - 1) * porPagina;
        int hasta = Math.min(total, desde + porPagina);
        return new Pagina(new ArrayList<>(l.subList(desde, hasta)), n, paginas, total, desde + 1);
    }

    /**
     * Numeros de pagina para los botones de abajo: siempre la primera y la ultima, y
     * dos a cada lado de la actual. -1 es "...". Con 7 paginas o menos salen todas.
     */
    public static List<Integer> botones(int actual, int total) {
        List<Integer> b = new ArrayList<>();
        if (total <= 7) {
            for (int i = 1; i <= total; i++) {
                b.add(i);
            }
            return b;
        }
        int ini = Math.max(2, actual - 2);
        int fin = Math.min(total - 1, actual + 2);
        if (actual <= 3) { // al inicio: 1 2 3 4 5 ... N
            ini = 2;
            fin = 5;
        } else if (actual >= total - 2) { // al final: 1 ... N-4 N-3 N-2 N-1 N
            ini = total - 4;
            fin = total - 1;
        }
        b.add(1);
        if (ini > 2) {
            b.add(-1);
        }
        for (int i = ini; i <= fin; i++) {
            b.add(i);
        }
        if (fin < total - 1) {
            b.add(-1);
        }
        b.add(total);
        return b;
    }

    /**
     * CSV con punto y coma (lo que usa Excel en Colombia), con las mismas columnas que lee
     * ImportadorProductos: asi la lista exportada se puede editar en Excel y volver a importar.
     */
    public static String aCsv(List<Producto> productos, Map<Long, String> categorias) {
        return aCsv(productos, categorias, true);
    }

    /** conCosto = false: sin la columna precio_compra (para quien no puede ver los costos). */
    public static String aCsv(List<Producto> productos, Map<Long, String> categorias, boolean conCosto) {
        StringBuilder sb = new StringBuilder("\uFEFF"); // marca para que Excel lea bien las tildes
        sb.append(conCosto ? "nombre;codigo;unidad;precio_venta;precio_compra;stock;minimo;categoria\r\n"
                : "nombre;codigo;unidad;precio_venta;stock;minimo;categoria\r\n");
        for (Producto p : productos) {
            String cat = p.getCategoriaId() == null ? "" : categorias.getOrDefault(p.getCategoriaId(), "");
            sb.append(campo(p.getNombre())).append(';')
                    .append(p.getCodigo() == null ? "" : campo(p.getCodigo())).append(';')
                    .append(p.getUnidad().getCorto()).append(';')
                    .append(p.getPrecioVenta()).append(';');
            if (conCosto) {
                sb.append(p.getPrecioCompra()).append(';');
            }
            sb.append(cantidad(p.getStock())).append(';')
                    .append(cantidad(p.getMinimo())).append(';')
                    .append(campo(cat)).append("\r\n");
        }
        return sb.toString();
    }

    /** Milesimas a texto con coma decimal: 1500 -> "1,5"; -3000 -> "-3". */
    public static String cantidad(long milesimas) {
        BigDecimal b = BigDecimal.valueOf(milesimas, 3).stripTrailingZeros();
        return (b.scale() < 0 ? b.setScale(0) : b).toPlainString().replace('.', ',');
    }

    /** Entre comillas si tiene separador o comillas (y las comillas se duplican). */
    private static String campo(String s) {
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
