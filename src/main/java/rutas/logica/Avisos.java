package rutas.logica;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hora prometida al cliente y mensaje de aviso por WhatsApp.
 * El aviso usa un enlace wa.me: abre WhatsApp con el texto listo y la persona solo le da enviar.
 */
public final class Avisos {

    /** Margen de la promesa: un poco antes y algo mas despues (los retrasos son mas comunes). */
    public static final int MINUTOS_ANTES = 5;
    public static final int MINUTOS_DESPUES = 10;

    private static final DateTimeFormatter SOLO_HORA = DateTimeFormatter.ofPattern("h:mm");
    // celular colombiano: 10 digitos empezando por 3, con o sin +57 y con espacios o guiones
    private static final Pattern CELULAR = Pattern.compile("(?<!\\d)(?:\\+?57[\\s-]?)?(3\\d{2})[\\s-]?(\\d{3})[\\s-]?(\\d{4})(?!\\d)");

    private Avisos() {
    }

    /** Ventana de llegada redondeada a 5 minutos: {desde, hasta}. */
    public static LocalTime[] ventana(LocalTime llegada) {
        LocalTime desde = redondear(llegada.minusMinutes(MINUTOS_ANTES), false);
        LocalTime hasta = redondear(llegada.plusMinutes(MINUTOS_DESPUES), true);
        return new LocalTime[]{desde, hasta};
    }

    private static LocalTime redondear(LocalTime t, boolean haciaArriba) {
        LocalTime base = t.withSecond(0).withNano(0);
        int resto = base.getMinute() % 5;
        if (resto == 0) {
            return base;
        }
        return haciaArriba ? base.plusMinutes(5 - resto) : base.minusMinutes(resto);
    }

    /** "entre 7:40 y 7:55 p.m." (la marca a.m./p.m. va una sola vez si es la misma). */
    public static String textoVentana(LocalTime llegada) {
        LocalTime[] v = ventana(llegada);
        boolean mismaMarca = (v[0].getHour() < 12) == (v[1].getHour() < 12);
        String desde = mismaMarca ? v[0].format(SOLO_HORA) : formato(v[0]);
        return "entre " + desde + " y " + formato(v[1]);
    }

    // se arma a mano: segun la version de Java, el formato del sistema mete espacios raros en "p. m."
    private static String formato(LocalTime t) {
        return t.format(SOLO_HORA) + (t.getHour() < 12 ? " a.m." : " p.m.");
    }

    /** Busca un celular dentro de un texto. Devuelve los 10 digitos o "" si no hay. */
    public static String extraerCelular(String texto) {
        if (texto == null) {
            return "";
        }
        Matcher m = CELULAR.matcher(texto);
        return m.find() ? m.group(1) + m.group(2) + m.group(3) : "";
    }

    /** El texto sin el celular (para que no estorbe al ubicar la direccion). */
    public static String quitarCelular(String texto) {
        return CELULAR.matcher(texto).replaceAll(" ").replaceAll("\\s{2,}", " ").trim();
    }

    /** Numero listo para wa.me (57 + 10 digitos) o "" si no es un celular valido. */
    public static String numeroWhatsApp(String telefono) {
        String cel = extraerCelular(telefono);
        return cel.isEmpty() ? "" : "57" + cel;
    }

    public static String mensaje(String cliente, String negocio, LocalTime llegada) {
        StringBuilder sb = new StringBuilder("Hola");
        if (cliente != null && !cliente.isBlank()) {
            sb.append(' ').append(primerNombre(cliente));
        }
        sb.append(", su pedido");
        if (negocio != null && !negocio.isBlank()) {
            sb.append(" de ").append(negocio.trim());
        }
        sb.append(" ya va en camino. Le llega ").append(textoVentana(llegada)).append(" ¡Gracias!");
        return sb.toString();
    }

    /**
     * Mensaje con la plata: el valor del domicilio y cuanto debe tener listo el cliente.
     * Si totalACobrar es 0 es porque ya pago por transferencia.
     */
    public static String mensaje(String cliente, String negocio, LocalTime llegada, long domicilio, long totalACobrar) {
        String base = mensaje(cliente, negocio, llegada);
        if (domicilio <= 0) {
            return base;
        }
        String plata = " Domicilio: " + Tarifa.pesos(domicilio) + "."
                + (totalACobrar > 0 ? " Total a pagar: " + Tarifa.pesos(totalACobrar) + "." : " Su pago ya quedó registrado.");
        int i = base.lastIndexOf(" ¡Gracias!");
        return base.substring(0, i) + plata + base.substring(i);
    }

    private static String primerNombre(String nombre) {
        String n = nombre.trim();
        int esp = n.indexOf(' ');
        return esp > 0 ? n.substring(0, esp) : n;
    }

    /** Enlace que abre el chat con el mensaje escrito. */
    public static String enlaceWhatsApp(String telefono, String mensaje) {
        String num = numeroWhatsApp(telefono);
        if (num.isEmpty()) {
            throw new IllegalArgumentException("El número no parece un celular de Colombia (10 dígitos, empieza por 3).");
        }
        return "https://wa.me/" + num + "?text=" + URLEncoder.encode(mensaje, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
