package rutas.modelo;

/**
 * Novedades en la via que reporta el operador: trancones, huecos, obras, cierres.
 * factor multiplica la velocidad del tramo (0 = no se puede pasar).
 */
public enum TipoIncidente {
    TRANCON("Trancón", 0.35),
    HUECOS("Huecos / vía en mal estado", 0.5),
    OBRA("Obra en la vía", 0.4),
    CERRADA("Vía cerrada", 0.0);

    private final String texto;
    private final double factor;

    TipoIncidente(String texto, double factor) {
        this.texto = texto;
        this.factor = factor;
    }

    public double getFactor() {
        return factor;
    }

    @Override
    public String toString() {
        return texto;
    }
}
