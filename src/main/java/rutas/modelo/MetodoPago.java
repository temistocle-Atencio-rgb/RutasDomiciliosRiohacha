package rutas.modelo;

/**
 * Formas de pagar en la caja (las que se usan en Colombia). Cada tienda escoge en
 * Configuración cuáles acepta; Efectivo siempre está.
 * El nombre del enum es lo que se guarda en la base: no se cambia.
 */
public enum MetodoPago {
    EFECTIVO("Efectivo", "Billetes y monedas. Siempre está activo."),
    NEQUI("Nequi", "Pago desde la app Nequi o con su código QR."),
    DAVIPLATA("Daviplata", "Pago desde la app Daviplata."),
    BREB("Bre-B", "Transferencia al instante con llave Bre-B (celular, cédula o correo)."),
    TRANSFERENCIA("Transferencia", "Transferencia desde cualquier banco a la cuenta de la tienda."),
    BANCOLOMBIA("Bancolombia", "Transferencia o QR de Bancolombia (también Bancolombia a la mano)."),
    TARJETA_DEBITO("Tarjeta débito", "Con datáfono."),
    TARJETA_CREDITO("Tarjeta crédito", "Con datáfono."),
    NU("Nu", "Pago desde la cuenta Nu."),
    DALE("Dale!", "Billetera digital del Grupo Aval."),
    MERCADO_PAGO("Mercado Pago", "QR o link de Mercado Pago.");

    private final String texto;
    private final String detalle;

    MetodoPago(String texto, String detalle) {
        this.texto = texto;
        this.detalle = detalle;
    }

    public String getTexto() {
        return texto;
    }

    public String getDetalle() {
        return detalle;
    }

    /** Nombre corto para los botones del cobro (el logo de la tarjeta ya dice que es tarjeta). */
    public String getCorto() {
        return this == TARJETA_DEBITO ? "Débito" : this == TARJETA_CREDITO ? "Crédito" : texto;
    }

    /** Archivo del logo en /imagenes/pagos/. */
    public String getLogo() {
        return "/imagenes/pagos/" + name().toLowerCase() + ".png";
    }

    @Override
    public String toString() {
        return texto;
    }
}
