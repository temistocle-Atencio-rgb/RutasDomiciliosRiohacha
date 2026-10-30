package rutas.modelo;

/** Como se vende un producto: por unidad o a granel por peso. */
public enum Unidad {
    UNIDAD("und"),
    LIBRA("lb"),
    KILO("kg");

    private final String corto;

    Unidad(String corto) {
        this.corto = corto;
    }

    public String getCorto() {
        return corto;
    }
}
