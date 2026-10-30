package rutas.logica;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import rutas.modelo.LineaVenta;
import rutas.modelo.Producto;

/** La venta que se esta armando en la caja. No toca la base de datos. */
public class Carrito {

    private final List<LineaVenta> lineas = new ArrayList<>();

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

    public void quitar(int i) {
        lineas.remove(i);
    }

    public void vaciar() {
        lineas.clear();
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
