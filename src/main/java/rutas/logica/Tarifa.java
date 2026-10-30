package rutas.logica;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuanto cuesta un domicilio segun la distancia real por las calles (no en linea recta).
 * Una tarifa base cubre los primeros kilometros; despues se cobra cada kilometro extra.
 * El valor se sube al multiplo de 500 pesos siguiente para que sea facil de cobrar.
 */
public class Tarifa {

    public static final int REDONDEO = 500;

    private final int base;        // pesos
    private final double kmIncluidos;
    private final int porKm;       // pesos por cada km despues de los incluidos

    public Tarifa(int base, double kmIncluidos, int porKm) {
        this.base = base;
        this.kmIncluidos = kmIncluidos;
        this.porKm = porKm;
    }

    public int getBase() { return base; }
    public double getKmIncluidos() { return kmIncluidos; }
    public int getPorKm() { return porKm; }

    /** Valor del domicilio para una distancia en metros. */
    public int calcular(double metros) {
        double km = metros / 1000.0;
        double valor = base + Math.max(0, km - kmIncluidos) * porKm;
        long redondeado = (long) Math.ceil(Math.round(valor) / (double) REDONDEO) * REDONDEO;
        return (int) redondeado;
    }

    /** "$25.500" con punto de miles, como se escribe en Colombia. */
    public static String pesos(long valor) {
        String s = Long.toString(Math.abs(valor));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) {
                sb.append('.');
            }
            sb.append(s.charAt(i));
        }
        return (valor < 0 ? "-$" : "$") + sb;
    }

    // solo se lee un valor que venga con signo de pesos, para no confundirlo con la direccion
    private static final Pattern VALOR = Pattern.compile("\\$\\s*(\\d{1,3}(?:[.,]\\d{3})+|\\d+)");

    /** Lee "$25.000" o "$25000" dentro de un texto. 0 si no hay. */
    public static int leerPesos(String texto) {
        if (texto == null) {
            return 0;
        }
        Matcher m = VALOR.matcher(texto);
        if (!m.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(1).replace(".", "").replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** El texto sin el valor en pesos. */
    public static String quitarPesos(String texto) {
        return VALOR.matcher(texto).replaceAll(" ").replaceAll("\\s{2,}", " ").trim();
    }

    /** "$3.000 hasta 2 km y $700 por km adicional" */
    public String descripcion() {
        String km = kmIncluidos == Math.floor(kmIncluidos) ? String.valueOf((int) kmIncluidos) : String.valueOf(kmIncluidos);
        return pesos(base) + " hasta " + km + " km y " + pesos(porKm) + " por km adicional";
    }
}
