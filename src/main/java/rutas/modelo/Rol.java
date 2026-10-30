package rutas.modelo;

/** Quien usa la caja: el administrador ve todo; el cajero vende y hace domicilios. */
public enum Rol {
    ADMIN("Administrador"),
    CAJERO("Cajero");

    private final String texto;

    Rol(String texto) {
        this.texto = texto;
    }

    public String getTexto() {
        return texto;
    }
}
