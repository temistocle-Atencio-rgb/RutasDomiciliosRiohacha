package rutas.vista;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import rutas.logica.Tarifa;
import rutas.modelo.LineaVenta;
import rutas.modelo.MetodoPago;
import rutas.modelo.Pago;

/**
 * Comprobante de venta para imprimir (o guardar como PDF desde la impresora de Windows).
 * Dice claramente que no es factura electronica.
 */
class Comprobante implements Printable {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm a");

    final String negocio;
    final long numero;
    final LocalDateTime fecha;
    final String atendio;
    final String cliente;
    final List<LineaVenta> lineas;
    final long total;
    final List<Pago> pagos;
    final long recibido;
    final long vueltas;
    String nota; // la nota que el cajero escribio en la venta (puede ser null)

    Comprobante(String negocio, long numero, LocalDateTime fecha, String atendio, String cliente,
            List<LineaVenta> lineas, long total, List<Pago> pagos, long recibido, long vueltas) {
        this.negocio = negocio == null || negocio.isBlank() ? "DomiRuta" : negocio;
        this.numero = numero;
        this.fecha = fecha;
        this.atendio = atendio;
        this.cliente = cliente;
        this.lineas = new ArrayList<>(lineas);
        this.total = total;
        this.pagos = new ArrayList<>(pagos);
        this.recibido = recibido;
        this.vueltas = vueltas;
    }

    static String numeroTexto(long n) {
        return String.format("%05d", n);
    }

    /** Lineas de texto del comprobante (tambien se muestran en "Ver la venta"). */
    List<String[]> filas() {
        List<String[]> f = new ArrayList<>();
        for (LineaVenta l : lineas) {
            String cant = l.getProducto().getUnidad() == rutas.modelo.Unidad.UNIDAD
                    ? PanelProductos.cantidad(l.getCantidad()) + " x"
                    : CuadroGranel.peso(l.getCantidad(), l.getProducto());
            f.add(new String[]{cant + " " + l.getProducto().getNombre(), Tarifa.pesos(l.getSubtotal())});
            if (l.getDescuento() > 0) {
                f.add(new String[]{l.getPorcentajeGuardado() > 0
                    ? "   descuento " + rutas.logica.Carrito.textoPorcentaje(l.getPorcentajeGuardado()) + " %"
                    : "   descuento",
                    "-" + Tarifa.pesos(l.getDescuento())});
            }
        }
        return f;
    }

    @Override
    public int print(Graphics g0, PageFormat pf, int pagina) {
        if (pagina > 0) {
            return NO_SUCH_PAGE;
        }
        Graphics2D g = (Graphics2D) g0;
        Tema.suavizar(g);
        g.translate(pf.getImageableX(), pf.getImageableY());
        int ancho = (int) Math.min(pf.getImageableWidth(), 300); // tira angosta, como un tiquete
        dibujar(g, ancho);
        return PAGE_EXISTS;
    }

    /** Dibuja el comprobante desde (0,0) con el ancho dado. Devuelve el alto usado. */
    int dibujar(Graphics2D g, int ancho) {
        g.setColor(Color.BLACK);
        int y = 0;
        y = centrado(g, negocio, Tema.subtitulo(14), ancho, y + 16);
        y = centrado(g, "Comprobante de venta No. " + numeroTexto(numero), Tema.textoFuerte(10.5f), ancho, y + 16);
        y = centrado(g, fecha.format(FECHA).replace("AM", "a. m.").replace("PM", "p. m."), Tema.texto(9.5f), ancho, y + 14);
        y += 6;
        Font f = Tema.texto(9.5f);
        y = izq(g, "Atendió: " + atendio, f, y + 13);
        if (cliente != null && !cliente.isBlank()) {
            y = izq(g, "Cliente: " + cliente, f, y + 13);
        }
        if (nota != null && !nota.isBlank()) {
            g.setFont(f);
            for (String renglon : partir("Nota: " + nota, g.getFontMetrics(), ancho)) {
                y = izq(g, renglon, f, y + 13);
            }
        }
        y = linea(g, ancho, y + 8);
        for (String[] fila : filas()) {
            y = par(g, fila[0], fila[1], f, ancho, y + 14);
        }
        y = linea(g, ancho, y + 8);
        y = par(g, "TOTAL", Tarifa.pesos(total), Tema.subtitulo(12), ancho, y + 18);
        y += 4;
        for (Pago p : pagos) {
            if (p.getValor() > 0) {
                y = par(g, p.getMetodo().getTexto(), Tarifa.pesos(p.getValor()), f, ancho, y + 13);
            }
        }
        if (recibido > 0) {
            y = par(g, "Recibido en efectivo", Tarifa.pesos(recibido), f, ancho, y + 13);
            y = par(g, "Vueltas", Tarifa.pesos(vueltas), f, ancho, y + 13);
        }
        y = linea(g, ancho, y + 8);
        y = centrado(g, "Este comprobante no es una factura electrónica.", Tema.texto(8.5f), ancho, y + 14);
        y = centrado(g, "Gracias por su compra", Tema.textoFuerte(9.5f), ancho, y + 14);
        return y + 8;
    }

    private static int centrado(Graphics2D g, String s, Font f, int ancho, int y) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, Math.max(0, (ancho - fm.stringWidth(s)) / 2), y);
        return y;
    }

    /** Parte un texto largo en renglones que quepan en el ancho del tiquete. */
    static List<String> partir(String texto, FontMetrics fm, int ancho) {
        List<String> r = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String palabra : texto.split("\\s+")) {
            // una palabra mas ancha que el tiquete (un numero de cuenta largo) se parte por letras
            while (fm.stringWidth(palabra) > ancho && palabra.length() > 1) {
                int n = palabra.length() - 1;
                while (n > 1 && fm.stringWidth(palabra.substring(0, n)) > ancho) {
                    n--;
                }
                if (actual.length() > 0) {
                    r.add(actual.toString());
                    actual = new StringBuilder();
                }
                r.add(palabra.substring(0, n));
                palabra = palabra.substring(n);
            }
            String prueba = actual.length() == 0 ? palabra : actual + " " + palabra;
            if (fm.stringWidth(prueba) <= ancho || actual.length() == 0) {
                actual = new StringBuilder(prueba);
            } else {
                r.add(actual.toString());
                actual = new StringBuilder(palabra);
            }
        }
        if (actual.length() > 0) {
            r.add(actual.toString());
        }
        return r;
    }

    private static int izq(Graphics2D g, String s, Font f, int y) {
        g.setFont(f);
        g.drawString(s, 0, y);
        return y;
    }

    private static int par(Graphics2D g, String a, String b, Font f, int ancho, int y) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int wb = fm.stringWidth(b);
        String ra = a;
        while (ra.length() > 3 && fm.stringWidth(ra) > ancho - wb - 10) {
            ra = ra.substring(0, ra.length() - 2);
        }
        if (!ra.equals(a)) {
            ra = ra + "…";
        }
        g.drawString(ra, 0, y);
        g.drawString(b, ancho - wb, y);
        return y;
    }

    private static int linea(Graphics2D g, int ancho, int y) {
        float[] guion = {3f, 3f};
        java.awt.Stroke antes = g.getStroke();
        g.setStroke(new java.awt.BasicStroke(0.6f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 1f,
                guion, 0f));
        g.drawLine(0, y, ancho, y);
        g.setStroke(antes);
        return y;
    }

    /** Pagos para mostrar: el efectivo como lo que se cobro (no lo que se recibio). */
    static List<Pago> pagosNetos(List<Pago> pagos, long vueltas) {
        List<Pago> r = new ArrayList<>();
        long quitar = vueltas;
        for (Pago p : pagos) {
            long v = p.getValor();
            if (p.getMetodo() == MetodoPago.EFECTIVO && quitar > 0) {
                long q = Math.min(v, quitar);
                v -= q;
                quitar -= q;
            }
            r.add(new Pago(p.getMetodo(), v));
        }
        return r;
    }

    void imprimirEn(Graphics g, int ancho) {
        dibujar((Graphics2D) g, ancho);
    }
}
