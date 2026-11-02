package rutas.modelo;

import java.time.LocalDate;

/** Lote de un producto: numero que trae el empaque, cuando vence y cuanto hay (en milesimas). */
public class Lote {

    private final String numero;
    private final LocalDate vence;
    private final long cantidad;

    public Lote(String numero, LocalDate vence, long cantidad) {
        this.numero = numero == null ? "" : numero.trim();
        this.vence = vence;
        this.cantidad = cantidad;
    }

    public String getNumero() {
        return numero;
    }

    public LocalDate getVence() {
        return vence;
    }

    public long getCantidad() {
        return cantidad;
    }
}
