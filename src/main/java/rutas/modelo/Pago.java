package rutas.modelo;

/** Una parte del pago: cuanto y por cual medio. */
public class Pago {

    private final MetodoPago metodo;
    private final long valor;

    public Pago(MetodoPago metodo, long valor) {
        if (valor < 0) {
            throw new IllegalArgumentException("El pago no puede ser negativo");
        }
        this.metodo = metodo;
        this.valor = valor;
    }

    public MetodoPago getMetodo() {
        return metodo;
    }

    public long getValor() {
        return valor;
    }
}
