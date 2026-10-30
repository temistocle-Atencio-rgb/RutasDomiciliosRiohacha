package rutas.modelo;

/** Formas de pagar en la caja. */
public enum MetodoPago {
    EFECTIVO("Efectivo"),
    NEQUI("Nequi"),
    DAVIPLATA("Daviplata"),
    TRANSFERENCIA("Transferencia");

    private final String texto;

    MetodoPago(String texto) {
        this.texto = texto;
    }

    public String getTexto() {
        return texto;
    }
}
