package rutas.logica;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import rutas.modelo.LineaVenta;
import rutas.modelo.Producto;

/** La venta que se esta armando en la caja. No toca la base de datos. */
public class Carrito {

    private final List<LineaVenta> lineas = new ArrayList<>();
    private String nota;

    /** Maximo de letras de la nota de la venta. */
    public static final int MAX_NOTA = 200;

    /** Agrega una unidad (o un kilo/libra). Si el producto ya esta, suma. */
    public LineaVenta agregar(Producto p) {
        return agregar(p, 1000);
    }

    public LineaVenta agregar(Producto p, long cantidad) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor que cero");
        }
        for (LineaVenta l : lineas) {
            if (l.getProducto().getId() == p.getId() && !l.esPorValor()) {
                l.setCantidad(l.getCantidad() + cantidad);
                return l;
            }
        }
        LineaVenta l = new LineaVenta(p, cantidad, p.getPrecioVenta(), 0, null);
        lineas.add(l);
        return l;
    }

    /** "Dos mil de azucar": calcula el peso y deja el subtotal fijo en esa plata. */
    public LineaVenta agregarPorValor(Producto p, long pesos) {
        long cantidad = Granel.cantidadPorValor(pesos, p.getPrecioVenta());
        LineaVenta l = new LineaVenta(p, cantidad, p.getPrecioVenta(), 0, pesos);
        lineas.add(l);
        return l;
    }

    /** Para volver a armar una venta guardada en espera. */
    public void agregarLinea(LineaVenta l) {
        lineas.add(l);
    }

    public void cambiarCantidad(int i, long cantidad) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor que cero");
        }
        LineaVenta l = lineas.get(i);
        l.setCantidad(cantidad);
        if (l.getDescuento() > l.getBruto()) {
            l.setDescuento(l.getBruto());
        }
    }

    public void cambiarPrecio(int i, long precio) {
        if (precio <= 0) {
            throw new IllegalArgumentException("El precio debe ser mayor que cero");
        }
        LineaVenta l = lineas.get(i);
        l.setPrecio(precio);
        if (l.getDescuento() > l.getBruto()) {
            l.setDescuento(l.getBruto());
        }
    }

    public void cambiarDescuento(int i, long descuento) {
        LineaVenta l = lineas.get(i);
        if (descuento < 0) {
            throw new IllegalArgumentException("El descuento no puede ser negativo");
        }
        if (descuento > l.getBruto()) {
            throw new IllegalArgumentException("El descuento no puede ser mayor que el valor");
        }
        l.setDescuento(descuento);
    }

    /** Descuento de la linea en centesimas de % (1000 = 10 %). 0 lo quita. */
    public void cambiarPorcentaje(int i, long centesimas) {
        if (centesimas < 0 || centesimas > 10_000) {
            throw new IllegalArgumentException("El descuento va de 0 a 100 %");
        }
        lineas.get(i).setPorcentaje(centesimas);
    }

    /** "10" -> 1000, "12,5 %" -> 1250, "" -> 0. Hasta dos decimales. */
    public static long leerPorcentaje(String texto) {
        String t = texto == null ? "" : texto.replace("%", "").trim().replace(',', '.');
        if (t.isEmpty()) {
            return 0;
        }
        if (!t.matches("\\d{1,3}(\\.\\d{1,2})?")) {
            throw new IllegalArgumentException("Escriba el porcentaje con números, por ejemplo 10 o 12,5");
        }
        long c = Math.round(Double.parseDouble(t) * 100);
        if (c > 10_000) {
            throw new IllegalArgumentException("El descuento va de 0 a 100 %");
        }
        return c;
    }

    /** 1250 -> "12,5"; 1000 -> "10"; 0 -> "0". */
    public static String textoPorcentaje(long centesimas) {
        long entero = centesimas / 100;
        long resto = centesimas % 100;
        if (resto == 0) {
            return String.valueOf(entero);
        }
        String dec = resto % 10 == 0 ? String.valueOf(resto / 10) : String.format("%02d", resto);
        return entero + "," + dec;
    }

    public String getNota() {
        return nota;
    }

    /** Nota corta de la venta ("paga el viernes"). Vacia queda en null; se corta en 200 letras. */
    public void setNota(String texto) {
        String t = texto == null ? "" : texto.trim();
        nota = t.isEmpty() ? null : t.length() > MAX_NOTA ? t.substring(0, MAX_NOTA) : t;
    }

    public void quitar(int i) {
        lineas.remove(i);
    }

    public void vaciar() {
        lineas.clear();
        nota = null;
    }

    public List<LineaVenta> getLineas() {
        return Collections.unmodifiableList(lineas);
    }

    public long getTotal() {
        long t = 0;
        for (LineaVenta l : lineas) {
            t += l.getSubtotal();
        }
        return t;
    }

    public boolean estaVacio() {
        return lineas.isEmpty();
    }

    /** Cuantas lineas (productos distintos) tiene la venta. */
    public int cantidadProductos() {
        return lineas.size();
    }
}
