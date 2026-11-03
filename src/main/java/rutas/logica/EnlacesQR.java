package rutas.logica;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Los enlaces de los codigos QR. Todos salen de la direccion de la pagina (Configuración ›
 * Conexión) y del celular de «Mi negocio": si cambia el dominio o el numero, los QR se dibujan
 * de nuevo con lo nuevo. Ninguno lleva claves: el QR solo abre la pagina.
 */
public final class EnlacesQR {

    public enum Tipo {
        TIENDA("Tienda en línea", "Para los clientes: ven los productos y piden a domicilio.",
                "Escanea y haz tu pedido", "Pide a domicilio desde tu celular"),
        WHATSAPP("WhatsApp de la tienda", "Abre un chat con la tienda, con el saludo ya escrito.",
                "Escríbenos por WhatsApp", "Escanea y abre el chat con nosotros"),
        REPARTIDOR("Repartidores", "Para los domiciliarios: entran con su cuenta y ven sus entregas.",
                "Repartidores", "Escanea y entra con tu cuenta"),
        PEDIDOS("Pedidos en línea", "Para el encargado: entra con su celular y su clave de 6 números.",
                "Encargado de pedidos", "Escanea y entra con tu celular y tu clave");

        public final String nombre;
        public final String para;
        public final String titular;
        public final String subtitulo;

        Tipo(String nombre, String para, String titular, String subtitulo) {
            this.nombre = nombre;
            this.para = para;
            this.titular = titular;
            this.subtitulo = subtitulo;
        }
    }

    /** El saludo que queda escrito al abrir el chat. */
    public static final String SALUDO = "Hola, quiero hacer un pedido";

    private EnlacesQR() {
    }

    /** La carpeta de la pagina, terminada en «/» (sin index.html ni lo que va despues de «?»). */
    public static String base(String pagina) {
        String p = pagina == null ? "" : pagina.trim();
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int h = p.indexOf('#');
        if (h >= 0) {
            p = p.substring(0, h);
        }
        if (!p.matches("https?://[^\\s/]+(/.*)?")) {
            return "";
        }
        if (p.endsWith(".html")) {
            p = p.substring(0, p.lastIndexOf('/') + 1);
        }
        return p.endsWith("/") ? p : p + "/";
    }

    /** Celular de 10 numeros que empieza por 3 (quita +57 y espacios); "" si no es un celular. */
    public static String celular(String telefono) {
        String d = telefono == null ? "" : telefono.replaceAll("\\D", "");
        if (d.length() == 12 && d.startsWith("57")) {
            d = d.substring(2);
        }
        return d.matches("3\\d{9}") ? d : "";
    }

    /** El enlace de ese QR; "" si falta la pagina (o el celular, para WhatsApp). */
    public static String enlace(Tipo t, String pagina, String telefono) {
        if (t == Tipo.WHATSAPP) {
            String c = celular(telefono);
            return c.isEmpty() ? "" : "https://wa.me/57" + c + "?text="
                    + URLEncoder.encode(SALUDO, StandardCharsets.UTF_8).replace("+", "%20");
        }
        String b = base(pagina);
        if (b.isEmpty()) {
            return "";
        }
        switch (t) {
            case TIENDA:
                return b + "tienda.html";
            case PEDIDOS:
                return b + "pedidos.html";
            default:
                return b;
        }
    }

    /** Lo que identifica a los QR impresos: si cambia, los que ya estan pegados quedaron viejos. */
    public static String huella(String pagina, String telefono) {
        return base(pagina) + "|" + celular(telefono);
    }
}
