package rutas.logica;

/** Que se quiere minimizar al buscar la ruta. */
public enum Criterio {
    MAS_RAPIDA("Más rápida (tiempo)"),
    MAS_CORTA("Más corta (distancia)");

    private final String texto;

    Criterio(String texto) {
        this.texto = texto;
    }

    @Override
    public String toString() {
        return texto;
    }
}
