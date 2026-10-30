package rutas.modelo;

/**
 * Un producto dentro de la venta. Guarda su propio precio (el cajero lo puede
 * cambiar sin tocar el producto). Si se agrego "por valor" (dos mil de azucar),
 * el subtotal queda fijo en ese valor.
 */
public class LineaVenta {

    private final Producto producto;
    private long cantidad;      // milesimas
    private long precio;        // pesos por unidad de medida
    private long descuento;     // pesos
    private Long subtotalFijo;  // solo cuando se pidio por valor

    public LineaVenta(Producto producto, long cantidad, long precio, long descuento, Long subtotalFijo) {
        this.producto = producto;
        this.cantidad = cantidad;
        this.precio = precio;
        this.descuento = descuento;
        this.subtotalFijo = subtotalFijo;
    }

    /** Valor antes del descuento, redondeado al peso. */
    public long getBruto() {
        if (subtotalFijo != null) {
            return subtotalFijo;
        }
        return (precio * cantidad + 500) / 1000;
    }

    public long getSubtotal() {
        return getBruto() - descuento;
    }

    public Producto getProducto() {
        return producto;
    }

    public long getCantidad() {
        return cantidad;
    }

    public long getPrecio() {
        return precio;
    }

    public long getDescuento() {
        return descuento;
    }

    public boolean esPorValor() {
        return subtotalFijo != null;
    }

    public void setCantidad(long cantidad) {
        this.cantidad = cantidad;
        this.subtotalFijo = null; // ya no es "dos mil de", es un peso
    }

    public void setPrecio(long precio) {
        this.precio = precio;
        this.subtotalFijo = null;
    }

    public void setDescuento(long descuento) {
        this.descuento = descuento;
    }
}
