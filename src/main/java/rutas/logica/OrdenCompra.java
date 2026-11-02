package rutas.logica;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Orden de compra: el pedido que se le hace al proveedor ANTES de que llegue la mercancia.
 * Tiene lo mismo que una compra (productos, costo, impuesto, descuento) mas el proveedor de la
 * lista, la fecha estimada de llegada, el estado y las retenciones. No mueve el inventario: eso
 * pasa al recibirla, cuando se vuelve una compra.
 */
public class OrdenCompra extends Compra {

    /** Borrador → Pendiente (enviada) → Recibida en parte / Recibida, o Cancelada. */
    public enum Estado {
        BORRADOR("Borrador"), PENDIENTE("Pendiente"), PARCIAL("Recibida en parte"), RECIBIDA("Recibida"),
        CANCELADA("Cancelada");

        private final String texto;

        Estado(String texto) {
            this.texto = texto;
        }

        @Override
        public String toString() {
            return texto;
        }

        /** Se puede cambiar (productos, cantidades, proveedor). */
        public boolean editable() {
            return this == BORRADOR || this == PENDIENTE;
        }

        /** Todavia puede llegar mercancia. */
        public boolean porRecibir() {
            return this == PENDIENTE || this == PARCIAL;
        }
    }

    /** Retencion en la fuente por compras (2,5 %), lo mas comun; se puede cambiar en la orden. */
    public static final long RETENCION_POR_DEFECTO = 250;

    private long id;
    private long numero;
    private Long proveedorId;
    private LocalDate fecha = LocalDate.now();
    private LocalDate fechaEstimada;
    private LocalDateTime creada = LocalDateTime.now();
    private Estado estado = Estado.BORRADOR;
    private boolean conRetencion;
    private long retencionPct = RETENCION_POR_DEFECTO; // centesimas de %
    private String responsable = "";
    private String compras = ""; // numeros de las compras con que se recibio ("12, 15")

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getNumero() {
        return numero;
    }

    public void setNumero(long numero) {
        this.numero = numero;
    }

    public Long getProveedorId() {
        return proveedorId;
    }

    public void setProveedorId(Long proveedorId) {
        this.proveedorId = proveedorId;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public void setFecha(LocalDate fecha) {
        this.fecha = fecha;
    }

    public LocalDate getFechaEstimada() {
        return fechaEstimada;
    }

    public void setFechaEstimada(LocalDate fechaEstimada) {
        this.fechaEstimada = fechaEstimada;
    }

    public LocalDateTime getCreada() {
        return creada;
    }

    public void setCreada(LocalDateTime creada) {
        this.creada = creada;
    }

    public Estado getEstado() {
        return estado;
    }

    public void setEstado(Estado estado) {
        this.estado = estado;
    }

    public boolean isConRetencion() {
        return conRetencion;
    }

    public void setConRetencion(boolean conRetencion) {
        this.conRetencion = conRetencion;
    }

    public long getRetencionPct() {
        return retencionPct;
    }

    public void setRetencionPct(long retencionPct) {
        this.retencionPct = retencionPct;
    }

    public String getResponsable() {
        return responsable;
    }

    public void setResponsable(String responsable) {
        this.responsable = responsable == null ? "" : responsable;
    }

    public String getCompras() {
        return compras;
    }

    public void setCompras(String compras) {
        this.compras = compras == null ? "" : compras;
    }

    /** Retencion en la fuente: porcentaje sobre la base (bruto menos descuentos, sin IVA). */
    public long getRetencion() {
        if (!conRetencion) {
            return 0;
        }
        return Math.round(Math.max(0, getBruto() - getDescuentos()) * retencionPct / 10000.0);
    }

    /** Lo que se le paga al proveedor: el total menos la retencion. */
    public long getTotalOrden() {
        return Math.max(0, getTotal() - getRetencion());
    }

    /** Pendiente y con la fecha estimada ya pasada. */
    public boolean atrasada(LocalDate hoy) {
        return estado.porRecibir() && fechaEstimada != null && fechaEstimada.isBefore(hoy);
    }

    /** Para Enviar o Procesar: proveedor, productos y lo de la compra. */
    public void validarOrden() {
        if (getProveedor().isBlank()) {
            throw new IllegalArgumentException("Escoja el proveedor de la orden");
        }
        if (getLineas().isEmpty()) {
            throw new IllegalArgumentException("Agregue al menos un producto a la orden");
        }
        if (fechaEstimada != null && fecha != null && fechaEstimada.isBefore(fecha)) {
            throw new IllegalArgumentException("La fecha estimada no puede ser antes de la fecha de la orden");
        }
        if (retencionPct < 0 || retencionPct > 10000) {
            throw new IllegalArgumentException("La retención va de 0 % a 100 %");
        }
        try {
            validar();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage().replace("a la compra", "a la orden")
                    .replace("que la compra", "que la orden"));
        }
    }
}
