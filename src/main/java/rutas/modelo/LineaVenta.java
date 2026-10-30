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
    private long porcentaje;    // descuento en centesimas de % (1000 = 10 %); 0 = sin porcentaje

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

    /**
     * El descuento en centesimas de % (1000 = 10 %). Si se dio en pesos, se calcula
     * cuanto % es eso para mostrarlo.
     */
    public long getPorcentaje() {
        if (porcentaje > 0) {
            return porcentaje;
        }
        long bruto = getBruto();
        return descuento <= 0 || bruto <= 0 ? 0 : (descuento * 10_000 + bruto / 2) / bruto;
    }

    /** El % que se escribio (0 si el descuento esta en pesos o no hay). Es lo que se guarda en la base. */
    public long getPorcentajeGuardado() {
        return porcentaje;
    }

    public void setCantidad(long cantidad) {
        this.cantidad = cantidad;
        this.subtotalFijo = null; // ya no es "dos mil de", es un peso
        recalcular();
    }

    public void setPrecio(long precio) {
        this.precio = precio;
        this.subtotalFijo = null;
        recalcular();
    }

    /** Descuento en pesos: deja de ser porcentaje. */
    public void setDescuento(long descuento) {
        this.descuento = descuento;
        this.porcentaje = 0;
    }

    /** Descuento en %: se vuelve a calcular solo si cambia la cantidad o el precio. */
    public void setPorcentaje(long centesimas) {
        this.porcentaje = centesimas;
        if (centesimas == 0) {
            this.descuento = 0;
        }
        recalcular();
    }

    private void recalcular() {
        if (porcentaje > 0) {
            descuento = (getBruto() * porcentaje + 5_000) / 10_000;
        }
    }
}
