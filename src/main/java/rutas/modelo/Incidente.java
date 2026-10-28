package rutas.modelo;

import java.time.LocalDateTime;

/** Una novedad reportada sobre un tramo de via. */
public class Incidente {

    private final int via;
    private final TipoIncidente tipo;
    private final String nota;
    private final LocalDateTime fecha;

    public Incidente(int via, TipoIncidente tipo, String nota, LocalDateTime fecha) {
        this.via = via;
        this.tipo = tipo;
        this.nota = nota;
        this.fecha = fecha;
    }

    public int getVia() { return via; }
    public TipoIncidente getTipo() { return tipo; }
    public String getNota() { return nota; }
    public LocalDateTime getFecha() { return fecha; }
}
