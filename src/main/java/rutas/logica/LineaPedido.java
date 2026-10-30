package rutas.logica;

import java.util.regex.Pattern;

/**
 * Lee una linea de pedido como llega por WhatsApp, por ejemplo:
 * "Ana 300 123 4567, Calle 15 # 10-20 ya pagó $25.000 URGENTE".
 * Saca las marcas (urgente, pagado), el celular y el valor, y deja solo
 * el nombre y la direccion para ubicarla en el mapa.
 */
public final class LineaPedido {

    // (?iU): sin importar mayusculas y con \b que entiende tildes (sin U, "pagó" no termina en \b)
    private static final Pattern URGENTE = Pattern.compile("(?iU)\\burgente\\b");
    private static final Pattern PAGADO = Pattern.compile(
            "(?iU)\\b(ya\\s+pag[oó](\\s+por\\s+transferencia)?|pag[oó]\\s+por\\s+transferencia|pagado|transferencia)\\b");

    private boolean urgente;
    private boolean pagado;
    private String celular = "";
    private int valor;
    private String texto = "";

    private LineaPedido() {
    }

    public static LineaPedido leer(String linea) {
        LineaPedido p = new LineaPedido();
        String l = linea == null ? "" : linea.trim();
        p.urgente = URGENTE.matcher(l).find();
        p.pagado = PAGADO.matcher(l).find();
        p.celular = Avisos.extraerCelular(l);
        p.valor = Tarifa.leerPesos(l);
        String limpia = Tarifa.quitarPesos(Avisos.quitarCelular(l));
        limpia = PAGADO.matcher(limpia).replaceAll(" ");
        limpia = URGENTE.matcher(limpia).replaceAll(" ");
        p.texto = limpia.replaceAll("\\s{2,}", " ")
                .replaceAll("\\s+,", ",")
                .replaceAll("^[\\s,;:\\-]+|[\\s,;:\\-]+$", "")
                .trim();
        return p;
    }

    public boolean isUrgente() { return urgente; }
    public boolean isPagado() { return pagado; }
    public String getCelular() { return celular; }
    public int getValor() { return valor; }
    /** Lo que queda: nombre del cliente (si viene) y la direccion. */
    public String getTexto() { return texto; }
}
