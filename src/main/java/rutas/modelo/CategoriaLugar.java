package rutas.modelo;

/** Categorias de los lugares con nombre del mapa. */
public enum CategoriaLugar {
    RESTAURANTE("Restaurante"),
    DROGUERIA("Droguería"),
    TIENDA("Tienda / supermercado"),
    SALUD("Salud"),
    EDUCACION("Educación"),
    BANCO("Banco"),
    GASOLINERA("Estación de servicio"),
    HOTEL("Hotel"),
    BARRIO("Barrio"),
    GOBIERNO("Entidad pública"),
    RECREACION("Recreación"),
    TURISMO("Turismo"),
    OTRO("Otro");

    private final String texto;

    CategoriaLugar(String texto) {
        this.texto = texto;
    }

    public static CategoriaLugar desde(String s) {
        try {
            return valueOf(s);
        } catch (IllegalArgumentException e) {
            return OTRO;
        }
    }

    @Override
    public String toString() {
        return texto;
    }
}
