package rutas.logica;

import java.util.ArrayList;
import java.util.List;
import rutas.modelo.Producto;

/**
 * Una compra a un proveedor: lo que llego, cuanto y a que costo. Al guardarla sube el stock,
 * queda el costo nuevo del producto (para la ganancia) y, si se escribio, el precio de venta nuevo.
 * La plata va en pesos enteros, las cantidades en milesimas y los porcentajes en centesimas (19 % = 1900).
 */
public class Compra {

    /** Una linea: un producto con su cantidad, costo, impuesto y descuento. */
    public static class Linea {
        private final Producto producto;
        private long cantidad;      // milesimas
        private long base;          // costo por unidad SIN impuesto
        private long impuesto;      // centesimas de %
        private long descuento;     // centesimas de % (sobre la linea)
        private Long precioVenta;   // nuevo precio de venta (null = no cambia)
        private Long costoEscrito;  // valor compra tal como lo escribieron (asi no se corre un peso al redondear)
        private long recibida;      // milesimas que ya llegaron (solo en las ordenes de compra)

        public Linea(Producto producto, long cantidad, long base, long impuesto) {
            this.producto = producto;
            this.cantidad = cantidad;
            this.base = base;
            this.impuesto = impuesto;
        }

        public Producto getProducto() {
            return producto;
        }

        public long getCantidad() {
            return cantidad;
        }

        public void setCantidad(long c) {
            cantidad = c;
        }

        public long getBase() {
            return base;
        }

        public void setBase(long b) {
            base = b;
            costoEscrito = null;
        }

        public long getImpuesto() {
            return impuesto;
        }

        public void setImpuesto(long i) {
            impuesto = i;
            costoEscrito = null; // la base se queda y el valor compra se recalcula
        }

        public long getDescuento() {
            return descuento;
        }

        public void setDescuento(long d) {
            descuento = d;
        }

        /** Lo que ya llego de esta linea (ordenes de compra). */
        public long getRecibida() {
            return recibida;
        }

        public void setRecibida(long r) {
            recibida = Math.max(0, r);
        }

        /** Lo que falta por llegar (nunca negativo). */
        public long getPendiente() {
            return Math.max(0, cantidad - recibida);
        }

        public Long getPrecioVenta() {
            return precioVenta;
        }

        public void setPrecioVenta(Long p) {
            precioVenta = p;
        }

        /** Costo por unidad CON impuesto ("Valor compra"). */
        public long getCosto() {
            return costoEscrito != null ? costoEscrito : Math.round(base * (10000.0 + impuesto) / 10000.0);
        }

        /** Escribir el costo con impuesto calcula la base sin impuesto (y el costo queda tal cual se escribio). */
        public void setCosto(long costo) {
            base = Math.round(costo * 10000.0 / (10000.0 + impuesto));
            costoEscrito = costo;
        }

        public Long getCostoEscrito() {
            return costoEscrito;
        }

        /** Valor de venta que se ve: el nuevo si lo escribieron, si no el actual del producto. */
        public long getPrecioVentaVisto(Producto actual) {
            return precioVenta != null ? precioVenta : actual.getPrecioVenta();
        }

        /** Base por la cantidad, antes de descuento. */
        public long getBruto() {
            return Math.round(base * cantidad / 1000.0);
        }

        public long getValorDescuento() {
            return Math.round(getBruto() * descuento / 10000.0);
        }

        public long getValorImpuesto() {
            return Math.round((getBruto() - getValorDescuento()) * impuesto / 10000.0);
        }

        public long getSubtotal() {
            return getBruto() - getValorDescuento() + getValorImpuesto();
        }

        /** Lo que de verdad costo cada unidad (con impuesto y descuento): queda como precio de compra. */
        public long getCostoNeto() {
            return Math.round(getCosto() * (10000.0 - descuento) / 10000.0);
        }
    }

    private final List<Linea> lineas = new ArrayList<>();
    private long descuentoGeneral; // pesos, sobre el total
    private String observaciones = "";
    private String proveedor = "";
    private String factura = "";
    private long bodegaId = rutas.modelo.Bodega.PUNTO_DE_VENTA;

    public List<Linea> getLineas() {
        return lineas;
    }

    /** Agrega el producto (si ya esta, le suma una unidad). Devuelve la linea. */
    public Linea agregar(Producto p, long impuesto) {
        for (Linea l : lineas) {
            if (l.producto.getId() == p.getId()) {
                l.cantidad += 1000;
                return l;
            }
        }
        // costo de partida: el ultimo precio de compra, que ya tenia el impuesto
        Linea l = new Linea(p, 1000, 0, impuesto);
        l.setCosto(p.getPrecioCompra());
        lineas.add(0, l); // lo nuevo arriba, como en la referencia
        return l;
    }

    public void quitar(Linea l) {
        lineas.remove(l);
    }

    public long getBruto() {
        long s = 0;
        for (Linea l : lineas) {
            s += l.getBruto();
        }
        return s;
    }

    /** Descuentos de las lineas mas el descuento general. */
    public long getDescuentos() {
        long s = descuentoGeneral;
        for (Linea l : lineas) {
            s += l.getValorDescuento();
        }
        return s;
    }

    public long getImpuestos() {
        long s = 0;
        for (Linea l : lineas) {
            s += l.getValorImpuesto();
        }
        return s;
    }

    public long getTotal() {
        return Math.max(0, getBruto() - getDescuentos() + getImpuestos());
    }

    public long getDescuentoGeneral() {
        return descuentoGeneral;
    }

    public void setDescuentoGeneral(long d) {
        descuentoGeneral = d;
    }

    public String getObservaciones() {
        return observaciones;
    }

    public void setObservaciones(String o) {
        observaciones = o == null ? "" : o;
    }

    public String getProveedor() {
        return proveedor;
    }

    public void setProveedor(String p) {
        proveedor = p == null ? "" : p.trim();
    }

    public String getFactura() {
        return factura;
    }

    public void setFactura(String f) {
        factura = f == null ? "" : f.trim();
    }

    public long getBodegaId() {
        return bodegaId;
    }

    public void setBodegaId(long b) {
        bodegaId = b;
    }

    /** Revisa todo antes de guardar; lanza IllegalArgumentException con un mensaje claro. */
    public void validar() {
        if (lineas.isEmpty()) {
            throw new IllegalArgumentException("Agregue al menos un producto a la compra");
        }
        for (Linea l : lineas) {
            String n = l.producto.getNombre();
            if (l.cantidad <= 0) {
                throw new IllegalArgumentException("La cantidad de «" + n + "» debe ser mayor que 0");
            }
            if (l.base < 0) {
                throw new IllegalArgumentException("El costo de «" + n + "» no puede ser negativo");
            }
            if (l.descuento < 0 || l.descuento > 10000) {
                throw new IllegalArgumentException("El descuento de «" + n + "» va de 0 % a 100 %");
            }
            if (l.precioVenta != null && l.precioVenta <= 0) {
                throw new IllegalArgumentException("El valor de venta de «" + n + "» debe ser mayor que 0");
            }
        }
        if (descuentoGeneral < 0) {
            throw new IllegalArgumentException("El descuento no puede ser negativo");
        }
        if (descuentoGeneral > getBruto() - getDescuentos() + descuentoGeneral + getImpuestos()) {
            throw new IllegalArgumentException("El descuento es más grande que la compra");
        }
    }

    /** Impuesto en centesimas a partir de lo que dice la ficha ("IVA 19%" + "INC 8%" = 2700). */
    public static long impuestoDe(List<String> impuestos) {
        long t = 0;
        for (String i : impuestos) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*%").matcher(i);
            if (m.find()) {
                t += Math.round(Double.parseDouble(m.group(1).replace(',', '.')) * 100);
            }
        }
        return t;
    }
}
