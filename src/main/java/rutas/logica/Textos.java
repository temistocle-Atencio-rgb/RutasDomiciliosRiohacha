package rutas.logica;

/**
 * Reglas comunes para los textos que escribe el usuario: espacios de mas, largo maximo y
 * caracteres permitidos. El largo se cuenta en caracteres reales (un emoji es uno solo).
 */
public final class Textos {

    public static final int NOMBRE_PRODUCTO = 120;
    public static final int DESCRIPCION = 500;
    public static final int GARANTIA = 300;
    public static final int CARACTERISTICA_NOMBRE = 40;
    public static final int CARACTERISTICA_VALOR = 120;
    public static final int NOTA = 300;
    public static final int DIRECCION = 150;

    /** Simbolos que si van en el nombre de un producto (grados, marca registrada...). */
    private static final String SIMBOLOS_PERMITIDOS = "°®™©";

    private Textos() {
    }

    /** Sin espacios a los lados y sin espacios dobles (los saltos de linea tambien quedan como un espacio). */
    public static String limpiar(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /** Para textos de varias lineas (descripcion, garantia): sin espacios a los lados ni dobles en cada linea. */
    public static String limpiarLineas(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (String linea : s.trim().split("\\r?\\n|\\r")) {
            if (b.length() > 0) {
                b.append('\n');
            }
            b.append(linea.trim().replaceAll("[ \\t\\x0B\\f]+", " "));
        }
        return b.toString().replaceAll("\\n{3,}", "\n\n");
    }

    /**
     * Revisa largo y caracteres. que: "El nombre", "La nota"... Los caracteres de control (los que no
     * se ven) nunca van; los saltos de linea solo si variasLineas.
     */
    public static void revisar(String valor, String que, int maximo, boolean variasLineas) {
        if (valor == null) {
            return;
        }
        int largo = valor.codePointCount(0, valor.length());
        if (largo > maximo) {
            throw new IllegalArgumentException(que + " tiene " + largo + " caracteres; el máximo es " + maximo);
        }
        valor.codePoints().forEach(c -> {
            boolean salto = c == '\n' || c == '\t';
            if (Character.isISOControl(c) && !(variasLineas && salto)) {
                throw new IllegalArgumentException(que + " tiene un carácter que no se ve (cópielo de nuevo o escríbalo)");
            }
        });
    }

    /**
     * Nombre de producto: lista blanca de letras (con tildes), numeros, espacios, signos de puntuacion,
     * $ + = % y los simbolos ° ® ™ ©. Sin emojis ni dibujos: no salen en la impresora del recibo.
     */
    public static void revisarNombreProducto(String nombre) {
        revisar(nombre, "El nombre", NOMBRE_PRODUCTO, false);
        nombre.codePoints().forEach(c -> {
            if (!permitidoEnNombre(c)) {
                throw new IllegalArgumentException("El nombre no puede llevar emojis ni dibujos («"
                        + new String(Character.toChars(c)) + "»)");
            }
        });
    }

    static boolean permitidoEnNombre(int c) {
        if (SIMBOLOS_PERMITIDOS.indexOf(c) >= 0) {
            return true;
        }
        switch (Character.getType(c)) {
            case Character.UPPERCASE_LETTER:
            case Character.LOWERCASE_LETTER:
            case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER:
            case Character.OTHER_LETTER:
            case Character.NON_SPACING_MARK:      // tildes combinadas
            case Character.COMBINING_SPACING_MARK:
            case Character.DECIMAL_DIGIT_NUMBER:
            case Character.LETTER_NUMBER:
            case Character.OTHER_NUMBER:          // ½ ²
            case Character.SPACE_SEPARATOR:
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.MATH_SYMBOL:           // + = < > ×
            case Character.CURRENCY_SYMBOL:       // $
                return c != 0xFE0F;               // selector de emoji
            default:
                return false;                     // emojis, dibujos, caracteres invisibles
        }
    }
}
