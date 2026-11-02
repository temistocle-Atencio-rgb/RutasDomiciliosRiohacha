package rutas.logica;

/** Valores en letras, como en las facturas: 12500 -> "Doce mil quinientos pesos m/cte". */
public final class Letras {

    private static final String[] UNIDADES = {"", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho",
        "nueve", "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho",
        "diecinueve", "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis",
        "veintisiete", "veintiocho", "veintinueve"};
    private static final String[] DECENAS = {"", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta",
        "ochenta", "noventa"};
    private static final String[] CENTENAS = {"", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos",
        "seiscientos", "setecientos", "ochocientos", "novecientos"};

    private Letras() {
    }

    /** "Doce mil quinientos pesos m/cte"; con millones exactos dice "de pesos" (un millón de pesos). */
    public static String pesos(long valor) {
        if (valor == 0) {
            return "Cero pesos m/cte";
        }
        if (valor == 1) {
            return "Un peso m/cte";
        }
        String t = apocope(numero(Math.abs(valor)));
        boolean exactoMillon = valor % 1_000_000 == 0;
        t = (valor < 0 ? "menos " : "") + t + (exactoMillon ? " de pesos m/cte" : " pesos m/cte");
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** El numero en palabras (hasta billones). "uno" se vuelve "un" delante de mil, millón y pesos. */
    static String numero(long n) {
        if (n == 0) {
            return "cero";
        }
        StringBuilder b = new StringBuilder();
        long millonesDeMillon = n / 1_000_000_000_000L;
        long millones = (n / 1_000_000) % 1_000_000;
        long resto = n % 1_000_000;
        if (millonesDeMillon > 0) {
            b.append(millonesDeMillon == 1 ? "un billón" : apocope(hastaMillon(millonesDeMillon)) + " billones");
        }
        if (millones > 0) {
            espacio(b).append(millones == 1 ? "un millón" : apocope(hastaMillon(millones)) + " millones");
        }
        if (resto > 0) {
            espacio(b).append(apocope(hastaMillon(resto)));
        }
        return b.toString();
    }

    private static StringBuilder espacio(StringBuilder b) {
        if (b.length() > 0) {
            b.append(' ');
        }
        return b;
    }

    /** 0..999999 */
    private static String hastaMillon(long n) {
        long miles = n / 1000;
        long resto = n % 1000;
        StringBuilder b = new StringBuilder();
        if (miles > 0) {
            b.append(miles == 1 ? "mil" : apocope(hastaMil((int) miles)) + " mil");
        }
        if (resto > 0) {
            espacio(b).append(hastaMil((int) resto));
        }
        return b.toString();
    }

    /** 1..999 */
    private static String hastaMil(int n) {
        if (n == 100) {
            return "cien";
        }
        int c = n / 100;
        int r = n % 100;
        StringBuilder b = new StringBuilder(CENTENAS[c]);
        if (r > 0) {
            espacio(b);
            if (r < 30) {
                b.append(UNIDADES[r]);
            } else {
                b.append(DECENAS[r / 10]);
                if (r % 10 > 0) {
                    b.append(" y ").append(UNIDADES[r % 10]);
                }
            }
        }
        return b.toString();
    }

    /** "veintiuno mil" -> "veintiún mil", "treinta y uno mil" -> "treinta y un mil", al final "uno" -> "un" (pesos). */
    private static String apocope(String s) {
        if (s.endsWith("veintiuno")) {
            return s.substring(0, s.length() - 9) + "veintiún";
        }
        if (s.endsWith("uno")) {
            return s.substring(0, s.length() - 3) + "un";
        }
        return s;
    }
}
