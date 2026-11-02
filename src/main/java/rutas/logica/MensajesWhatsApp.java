package rutas.logica;

import java.time.LocalTime;
import java.util.List;
import rutas.modelo.Parada;
import rutas.modelo.Salida;

/**
 * Los mensajes de WhatsApp con formato: *negrita*, _cursiva_ y > cita (WhatsApp los muestra así).
 * Sin emojis, como el manual de marca. Si el enlace de la página va primero, WhatsApp muestra
 * la tarjeta con el logo de DomiRuta.
 */
public final class MensajesWhatsApp {

    private MensajesWhatsApp() {
    }

    /**
     * Al domiciliario: a quién va, qué cobra y cuánto cambio lleva en cada entrega.
     * enlace "" = salió sin internet (no se pone la línea de marcar); mapa puede ser "".
     */
    public static String salida(String negocio, LocalTime hora, String domiciliario, List<Salida.Entrega> entregas,
                                String enlace, String mapa) {
        return salida(negocio, hora, domiciliario, entregas, enlace, mapa, null);
    }

    /** responderAntes: hasta qué hora puede aceptar (null = sin hora). */
    public static String salida(String negocio, LocalTime hora, String domiciliario, List<Salida.Entrega> entregas,
                                String enlace, String mapa, LocalTime responderAntes) {
        StringBuilder b = new StringBuilder();
        if (negocio != null && !negocio.isBlank()) {
            b.append('*').append(negocio.trim().toUpperCase()).append("*\n");
        }
        b.append("Salida de las ").append(hora.format(rutas.modelo.Ruta.FORMATO_HORA)).append("\n\n");
        int total = 0;
        for (Salida.Entrega e : entregas) {
            total += e.cobrar;
        }
        b.append("Hola *").append(primerNombre(domiciliario)).append("*, tienes *").append(entregas.size())
                .append(entregas.size() == 1 ? " entrega" : " entregas").append("*.\n");
        if (total > 0) {
            b.append("Cobras en total *").append(Tarifa.pesos(total)).append("*\n");
        }
        if (enlace != null && !enlace.isBlank()) {
            b.append("\n> Abre el enlace y toca *Acepto* (o *No puedo*)")
                    .append(responderAntes == null ? "" : " antes de las *" + responderAntes.format(rutas.modelo.Ruta.FORMATO_HORA) + "*")
                    .append(":\n").append(enlace).append('\n');
        }
        for (int i = 0; i < entregas.size(); i++) {
            Salida.Entrega e = entregas.get(i);
            Parada p = e.parada;
            boolean conNombre = !p.getNombre().equals(p.getDireccion());
            b.append("\n*").append(i + 1).append(". ").append(conNombre ? p.getNombre() : p.getDireccion()).append("*\n");
            if (conNombre) {
                b.append(p.getDireccion()).append('\n');
            }
            if (!p.getTelefono().isEmpty()) {
                b.append("Cel. ").append(celular(p.getTelefono())).append('\n');
            }
            if (!p.getNota().isEmpty()) {
                b.append("Nota: _").append(p.getNota().replace("_", " ")).append("_\n");
            }
            if (p.getVentaNumero() > 0) {
                b.append("Venta N.º ").append(String.format("%05d", p.getVentaNumero()));
                if (p.getProductos() > 0) {
                    b.append(" · ").append(p.getProductos()).append(p.getProductos() == 1 ? " producto" : " productos");
                }
                b.append('\n');
            }
            if (e.cobrar > 0) {
                b.append("Cobrar *").append(Tarifa.pesos(e.cobrar)).append('*');
                int cambio = p.getPagaCon() - e.cobrar;
                if (p.getPagaCon() > 0 && cambio > 0) {
                    b.append(" · paga con ").append(Tarifa.pesos(p.getPagaCon())).append(" → lleva *")
                            .append(Tarifa.pesos(cambio)).append("* de cambio");
                }
                b.append('\n');
            } else {
                b.append("_Ya pagó_\n");
            }
        }
        if (mapa != null && !mapa.isBlank()) {
            b.append("\nRuta en el mapa:\n").append(mapa);
        }
        return b.toString().trim();
    }

    /** Para el grupo o los independientes: «Hay domicilio… ¿Quién puede venir?». */
    public static String preguntar(String negocio, List<Parada> entregas, int domicilios) {
        StringBuilder b = new StringBuilder("*Hay domicilio");
        if (negocio != null && !negocio.isBlank()) {
            b.append(" en ").append(negocio.trim());
        }
        b.append("*\n").append(entregas.size()).append(entregas.size() == 1 ? " entrega" : " entregas");
        if (domicilios > 0) {
            b.append(" · domicilios *").append(Tarifa.pesos(domicilios)).append('*');
        }
        b.append('\n');
        for (Parada p : entregas) {
            b.append("\n- ").append(p.getDireccion());
        }
        b.append("\n\n¿Quién puede venir?");
        return b.toString();
    }

    /**
     * Pedido al proveedor (orden de compra): saludo al vendedor, los productos con su cantidad,
     * el total aproximado, cuando se necesita y la nota. Sin emojis.
     */
    public static String orden(String negocio, String numero, OrdenCompra o, rutas.modelo.Proveedor pv) {
        StringBuilder b = new StringBuilder();
        if (negocio != null && !negocio.isBlank()) {
            b.append('*').append(negocio.trim().toUpperCase()).append("*\n");
        }
        b.append("Orden de compra N.º ").append(numero).append("\n\n");
        String quien = pv != null && !pv.vendedor.isBlank() ? primerNombre(pv.vendedor) : "";
        b.append(quien.isEmpty() ? "Buenas, " : "Buenas, *" + quien + "*, ")
                .append("le hago este pedido").append(o.getProveedor().isBlank() ? "" : " para *" + o.getProveedor().trim() + "*")
                .append(":\n");
        for (Compra.Linea l : o.getLineas()) {
            rutas.modelo.Producto p = l.getProducto();
            String cant = p.getUnidad() == rutas.modelo.Unidad.UNIDAD ? ListadoProductos.cantidad(l.getCantidad())
                    : ListadoProductos.cantidad(l.getCantidad()) + " " + p.getUnidad().getCorto();
            b.append("\n- *").append(cant).append("* ").append(p.getNombre());
            if (p.getCodigo() != null && !p.getCodigo().isBlank()) {
                b.append(" _(").append(p.getCodigo()).append(")_");
            }
        }
        b.append("\n\nTotal aprox. *").append(Tarifa.pesos(o.getTotalOrden())).append('*');
        if (o.getFechaEstimada() != null) {
            java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("EEEE d 'de' MMMM",
                    new java.util.Locale("es", "CO"));
            b.append("\nLo necesitamos para el *").append(o.getFechaEstimada().format(f)).append('*');
        }
        if (!o.getObservaciones().isBlank()) {
            b.append("\n> ").append(o.getObservaciones().trim().replaceAll("\\s+", " "));
        }
        b.append("\n\nQuedo atento a la confirmación. Gracias.");
        return b.toString();
    }

    /** 3127778899 -> 312 777 8899 (se lee y se marca más fácil). */
    static String celular(String tel) {
        String c = Avisos.extraerCelular(tel);
        return c.length() == 10 ? c.substring(0, 3) + " " + c.substring(3, 6) + " " + c.substring(6) : tel;
    }

    private static String primerNombre(String nombre) {
        String n = nombre == null ? "" : nombre.trim();
        int i = n.indexOf(' ');
        return n.isEmpty() ? "" : i > 0 ? n.substring(0, i) : n;
    }

    /** El enlace personal del repartidor: con él abre «Mis salidas» (y puede instalar la app). */
    public static String enlacePersonal(String negocio, String domiciliario, String enlace) {
        StringBuilder b = new StringBuilder();
        if (negocio != null && !negocio.isBlank()) {
            b.append('*').append(negocio.trim().toUpperCase()).append("*\n\n");
        }
        b.append("Hola *").append(primerNombre(domiciliario)).append("*, este es tu enlace de repartidor:\n")
                .append(enlace).append("\n\n")
                .append("Ábrelo en tu celular: ahí ves tus salidas y marcas si estás *disponible*.\n")
                .append("Es solo tuyo y queda en el primer celular donde lo abras. No lo compartas.");
        return b.toString();
    }

    /** Al cliente: el enlace para ver por dónde va su pedido y el código por si el enlace no abre. */
    public static String seguimiento(String negocio, String cliente, String enlace, String paginaConsulta, String codigo) {
        StringBuilder b = new StringBuilder();
        if (negocio != null && !negocio.isBlank()) {
            b.append('*').append(negocio.trim().toUpperCase()).append("*\n\n");
        }
        String n = primerNombre(cliente);
        b.append(n.isEmpty() ? "Hola" : "Hola *" + n + "*").append(", tu pedido va en camino.\n")
                .append("Mira por dónde va aquí:\n").append(enlace).append("\n\n")
                .append("Si el enlace no abre, entra a ").append(paginaConsulta)
                .append(" y escribe el código *").append(codigo).append("* con los últimos 4 números de tu celular.");
        return b.toString();
    }
}
