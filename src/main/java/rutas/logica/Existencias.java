package rutas.logica;

import rutas.modelo.LineaVenta;
import rutas.modelo.Producto;

/**
 * Revisa si alcanza lo que hay de un producto para lo que se quiere vender.
 * No se vende mas de lo que hay; y se avisa cuando, con la venta, queda en el minimo o menos.
 */
public final class Existencias {

    public enum Estado { BIEN, POCAS, NO_ALCANZA, AGOTADO }

    private Existencias() {
    }

    /** Lo que ya hay de ese producto en la venta (en milesimas), sumando todas sus lineas. */
    public static long enVenta(Carrito c, long productoId) {
        long total = 0;
        for (LineaVenta l : c.getLineas()) {
            if (l.getProducto().getId() == productoId) {
                total += l.getCantidad();
            }
        }
        return total;
    }

    /** Revisa vender {@code totalEnVenta} milesimas del producto (lo que quedaria en la venta en total). */
    public static Revision revisar(Producto p, long totalEnVenta) {
        String u = p.getUnidad().getCorto();
        String n = p.getNombre();
        if (!p.isManejaInventario()) {
            return new Revision(Estado.BIEN, null); // servicios y recetas: no se cuentan existencias
        }
        if (p.getStock() <= 0) {
            return new Revision(Estado.AGOTADO, "No hay " + n + ": está agotado");
        }
        if (totalEnVenta > p.getStock()) {
            return new Revision(Estado.NO_ALCANZA, "Solo quedan " + ListadoProductos.cantidad(p.getStock()) + " " + u + " de " + n);
        }
        long quedan = p.getStock() - totalEnVenta;
        if (quedan == 0) {
            return new Revision(Estado.POCAS, "Con esta venta se acaba el " + n);
        }
        if (quedan <= p.getMinimo()) {
            return new Revision(Estado.POCAS, "Quedan " + ListadoProductos.cantidad(quedan) + " " + u + " de " + n);
        }
        return new Revision(Estado.BIEN, null);
    }

    /** Lo que salio de la revision. */
    public static class Revision {
        private final Estado estado;
        private final String mensaje;

        Revision(Estado estado, String mensaje) {
            this.estado = estado;
            this.mensaje = mensaje;
        }

        public Estado getEstado() {
            return estado;
        }

        /** Texto listo para mostrar (null si esta bien). */
        public String getMensaje() {
            return mensaje;
        }

        public boolean puedeVender() {
            return estado == Estado.BIEN || estado == Estado.POCAS;
        }
    }
}
