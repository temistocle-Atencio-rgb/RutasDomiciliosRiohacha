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
    rutas.modelo.Cliente datosCliente; // cedula, celular, direccion y correo para el recibo carta (null = solo el nombre)
    long valorDomicilio;               // lo que cobra el repartidor por el domicilio (0 si no es a domicilio)
    boolean contraEntrega;
    List<String> domicilio; // datos para el repartidor (null si no es a domicilio); "*" al inicio = en negrilla

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

    /** Fecha como sale en el recibo: "31/10/2026 4:00 p. m." */
    String fechaTexto() {
        return fecha.format(FECHA).replace("AM", "a. m.").replace("PM", "p. m.");
    }

    /** "Productos" del recibo: unidades de lo que va por unidad y 1 por cada cosa a granel. */
    /** «$4.400» por unidad; a granel con su medida: «25 g x $122.222/kg» (se entiende que es el precio del kilo). */
    static String precioUnitario(LineaVenta l) {
        rutas.modelo.Unidad u = l.getProducto().getUnidad();
        return Tarifa.pesos(l.getPrecio()) + (u == rutas.modelo.Unidad.UNIDAD ? "" : "/" + u.getCorto());
    }

    static long piezas(List<LineaVenta> lineas) {
        long piezas = 0;
        for (LineaVenta l : lineas) {
            piezas += l.getProducto().getUnidad() == rutas.modelo.Unidad.UNIDAD ? Math.max(1, l.getCantidad() / 1000) : 1;
        }
        return piezas;
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
        // El recibo se pide de su largo exacto (100 productos = un ticket largo). Si el controlador de la
        // impresora no deja una hoja tan larga, se reparte en varias seguidas sin perder nada.
        int ancho = (int) Math.floor(pf.getImageableWidth());
        double altoHoja = pf.getImageableHeight();
        int total = altoDe(ancho);
        int hojas = altoHoja <= 0 ? 1 : (int) Math.max(1, Math.ceil(total / altoHoja));
        if (pagina >= hojas) {
            return NO_SUCH_PAGE;
        }
        Graphics2D g = (Graphics2D) g0;
        Tema.suavizar(g);
        g.translate(pf.getImageableX(), pf.getImageableY());
        if (hojas > 1) {
            g.clip(new java.awt.geom.Rectangle2D.Double(0, 0, pf.getImageableWidth(), altoHoja));
            g.translate(0, -pagina * altoHoja);
        }
        dibujar(g, ancho);
        return PAGE_EXISTS;
    }

    /** Alto del recibo dibujado con ese ancho. */
    private int altoDe(int ancho) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        int h = dibujar(g, ancho);
        g.dispose();
        return h;
    }

    /** Ajustes de la tienda (logo, datos, papel). Si no hay, los de siempre. */
    rutas.logica.AjustesRecibo ajustes = new rutas.logica.AjustesRecibo();
    /** Recibo de prueba: lleva una regla en mm para revisar el ancho y los margenes. */
    boolean prueba;

    /** Ancho de escritura en puntos segun los ajustes (lo que se usa en pantalla). */
    int anchoPuntos() {
        return (int) Math.floor(Impresion.mmAPuntos(ajustes.anchoUtilMm()));
    }

    /** Alto que ocupa el recibo, en puntos (para pedir el papel justo). */
    int alto() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        int h = dibujar(g, anchoPuntos());
        g.dispose();
        return h;
    }

    /**
     * Dibuja el recibo desde (0,0) con el ancho dado (en puntos: 72 por pulgada). Devuelve el alto usado.
     * En papel angosto (58 mm) cada producto va en dos renglones; en 80 mm o mas, en uno.
     */
    int dibujar(Graphics2D g, int ancho) {
        rutas.logica.AjustesRecibo a = ajustes;
        float k = a.letra.getEscala();
        Font chica = Tema.texto(7.5f * k);
        Font normal = Tema.texto(8.5f * k);
        Font fuerte = Tema.textoFuerte(8.5f * k);
        Font titulo = Tema.textoFuerte(10.5f * k);
        int paso = Math.round(11 * k);
        g.setColor(Color.BLACK);
        int y = 0;

        // logo en blanco y negro, como sale en la termica
        java.awt.image.BufferedImage logo = LogoTermico.preparar(a.logo, (int) Math.round(a.puntosUtiles() * 0.8),
                a.umbralLogo, a.tramado);
        if (logo != null) {
            double escala = 72.0 / a.ppp; // de puntos de la impresora a puntos de dibujo
            int lw = (int) Math.round(logo.getWidth() * escala), lh = (int) Math.round(logo.getHeight() * escala);
            Object antes = g.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(logo, (ancho - lw) / 2, y + 2, lw, lh, null);
            if (antes != null) {
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, antes);
            }
            y += lh + 4;
        }
        // datos del negocio
        y = renglonesCentro(g, a.nombre.isBlank() ? negocio : a.nombre, titulo, ancho, y, Math.round(13 * k));
        if (!a.razonSocial.isBlank() && !a.razonSocial.equalsIgnoreCase(a.nombre)) {
            y = renglonesCentro(g, a.razonSocial, normal, ancho, y, paso);
        }
        if (!a.nit.isBlank()) {
            y = renglonesCentro(g, "NIT " + a.nit, normal, ancho, y, paso);
        }
        String dir = a.direccion + (a.telefono.isBlank() ? "" : (a.direccion.isBlank() ? "" : " · ") + "Tel. " + a.telefono);
        if (!dir.isBlank()) {
            y = renglonesCentro(g, dir, normal, ancho, y, paso);
        }
        if (!a.ciudad.isBlank()) {
            y = renglonesCentro(g, a.ciudad, normal, ancho, y, paso);
        }
        if (!a.regimen.isBlank() && !"No aplica".equals(a.regimen)) {
            y = renglonesCentro(g, a.regimen, normal, ancho, y, paso);
        }
        y = linea(g, ancho, y + 6);
        y = centrado(g, "RECIBO DE VENTA", titulo, ancho, y + Math.round(13 * k));
        y = centrado(g, "N.º " + numeroTexto(numero), normal, ancho, y + paso);
        y = linea(g, ancho, y + 6);

        y = izq(g, "Fecha: " + fecha.format(FECHA).replace("AM", "a. m.").replace("PM", "p. m."), normal, y + paso);
        y = izq(g, "Atendió: " + atendio, normal, y + paso);
        if (cliente != null && !cliente.isBlank()) {
            y = renglonesIzq(g, "Cliente: " + cliente, normal, ancho, y, paso);
        }
        if (nota != null && !nota.isBlank()) {
            y = renglonesIzq(g, "Nota: " + nota, normal, ancho, y, paso);
        }
        y = lineaSolida(g, ancho, y + 5);

        boolean angosto = ancho < 180; // menos de unos 63 mm utiles
        long piezas = 0;
        for (LineaVenta l : lineas) {
            String cant = l.getProducto().getUnidad() == rutas.modelo.Unidad.UNIDAD
                    ? PanelProductos.cantidad(l.getCantidad())
                    : CuadroGranel.peso(l.getCantidad(), l.getProducto());
            if (l.getProducto().getUnidad() == rutas.modelo.Unidad.UNIDAD) {
                piezas += Math.max(1, l.getCantidad() / 1000);
            } else {
                piezas++;
            }
            String nombre = l.getProducto().getNombre();
            String unitario = precioUnitario(l);
            String total = Tarifa.pesos(l.getBruto());
            if (angosto) {
                y = renglonesIzq(g, nombre, fuerte, ancho, y + 2, paso);
                y = par(g, cant + " x " + unitario, total, normal, ancho, y + paso);
            } else {
                g.setFont(normal);
                int wTotal = 0, wCant = 0; // columnas del ancho justo de lo que hay en esta venta
                for (LineaVenta x : lineas) {
                    String c2 = x.getProducto().getUnidad() == rutas.modelo.Unidad.UNIDAD
                            ? PanelProductos.cantidad(x.getCantidad()) : CuadroGranel.peso(x.getCantidad(), x.getProducto());
                    wCant = Math.max(wCant, g.getFontMetrics().stringWidth(c2 + " x " + precioUnitario(x)));
                    wTotal = Math.max(wTotal, g.getFontMetrics().stringWidth(Tarifa.pesos(x.getBruto())));
                }
                List<String> ren = partir(nombre, g.getFontMetrics(fuerte), ancho - wTotal - wCant - 12);
                y += paso + 2;
                g.setFont(fuerte);
                g.drawString(ren.get(0), 0, y);
                g.setFont(normal);
                String cu = cant + " x " + unitario;
                g.drawString(cu, ancho - wTotal - 6 - g.getFontMetrics().stringWidth(cu), y);
                g.drawString(total, ancho - g.getFontMetrics().stringWidth(total), y);
                for (int i = 1; i < ren.size(); i++) {
                    y = izq(g, ren.get(i), fuerte, y + paso);
                }
            }
            if (l.getDescuento() > 0) {
                y = par(g, l.getPorcentajeGuardado() > 0
                        ? "  Descuento " + rutas.logica.Carrito.textoPorcentaje(l.getPorcentajeGuardado()) + " %"
                        : "  Descuento", "-" + Tarifa.pesos(l.getDescuento()), normal, ancho, y + paso);
            }
        }
        y = lineaSolida(g, ancho, y + 6);
        y = par(g, "TOTAL", Tarifa.pesos(total), Tema.textoFuerte(12 * k), ancho, y + Math.round(15 * k));
        y += 3;
        for (Pago p : pagos) {
            if (p.getValor() > 0) {
                y = par(g, p.getMetodo().getTexto(), Tarifa.pesos(p.getValor()), normal, ancho, y + paso);
            }
        }
        if (recibido > 0) {
            y = par(g, "Recibido en efectivo", Tarifa.pesos(recibido), normal, ancho, y + paso);
            y = par(g, "Vueltas", Tarifa.pesos(vueltas), normal, ancho, y + paso);
        }
        y = par(g, "Ítems: " + lineas.size(), "Productos: " + piezas, chica, ancho, y + paso + 2);
        if (domicilio != null && !domicilio.isEmpty()) { // lo que necesita el repartidor
            y = linea(g, ancho, y + 6);
            y = centrado(g, "DOMICILIO", titulo, ancho, y + Math.round(13 * k));
            for (String r : domicilio) {
                boolean negrilla = r.startsWith("*");
                y = renglonesIzq(g, negrilla ? r.substring(1) : r, negrilla ? fuerte : normal, ancho, y, paso);
            }
        }
        y = linea(g, ancho, y + 6);
        if (!a.gracias.isBlank()) {
            y = renglonesCentro(g, a.gracias, fuerte, ancho, y + 2, paso);
        }
        if (!a.pie.isBlank()) {
            y = renglonesCentro(g, a.pie, chica, ancho, y, Math.round(10 * k));
        }
        y = renglonesCentro(g, "Este recibo no es una factura electrónica.", chica, ancho, y + 2, Math.round(10 * k));
        if (a.mostrarSistema) {
            y = renglonesCentro(g, "Sistema DomiRuta", chica, ancho, y, Math.round(10 * k));
        }
        if (prueba) {
            y = regla(g, ancho, y + 10, chica);
        }
        return y + 10;
    }

    /** Regla en milimetros a lo ancho del papel, para revisar que no se corte nada. */
    private int regla(Graphics2D g, int ancho, int y, Font f) {
        double ppm = 72 / 25.4;
        int mm = (int) Math.floor(ancho / ppm);
        g.drawLine(0, y, ancho, y);
        for (int i = 0; i <= mm; i++) {
            int x = (int) Math.round(i * ppm);
            int alto = i % 10 == 0 ? 6 : i % 5 == 0 ? 4 : 2;
            g.drawLine(x, y, x, y + alto);
        }
        g.drawLine(ancho - 1, y - 4, ancho - 1, y + 6); // borde derecho
        g.setFont(f);
        String t = "Ancho útil: " + PanelProductos.cantidad(Math.round(ajustes.anchoUtilMm() * 1000)) + " mm · "
                + ajustes.puntosUtiles() + " puntos";
        return centrado(g, t, f, ancho, y + 18);
    }

    private static int renglonesCentro(Graphics2D g, String texto, Font f, int ancho, int y, int paso) {
        g.setFont(f);
        for (String r : partir(texto, g.getFontMetrics(), ancho)) {
            y = centrado(g, r, f, ancho, y + paso);
        }
        return y;
    }

    private static int renglonesIzq(Graphics2D g, String texto, Font f, int ancho, int y, int paso) {
        g.setFont(f);
        for (String r : partir(texto, g.getFontMetrics(), ancho)) {
            y = izq(g, r, f, y + paso);
        }
        return y;
    }

    private static int lineaSolida(Graphics2D g, int ancho, int y) {
        java.awt.Stroke antes = g.getStroke();
        g.setStroke(new java.awt.BasicStroke(0.8f));
        g.drawLine(0, y, ancho, y);
        g.setStroke(antes);
        return y;
    }

    /** Recibo de ejemplo para la vista previa y la prueba de impresion. */
    static Comprobante ejemplo(rutas.logica.AjustesRecibo a) {
        rutas.modelo.Producto gaseosa = new rutas.modelo.Producto(1, "7702004003508", "Gaseosa Coca-Cola lata 330 ml",
                rutas.modelo.Unidad.UNIDAD, 3000, 2200, 0, 0, null, true);
        rutas.modelo.Producto arroz = new rutas.modelo.Producto(2, null, "Arroz Diana 500 g", rutas.modelo.Unidad.UNIDAD,
                2500, 2000, 0, 0, null, true);
        rutas.modelo.Producto azucar = new rutas.modelo.Producto(3, null, "Azúcar", rutas.modelo.Unidad.LIBRA, 2200, 1800,
                0, 0, null, true);
        List<LineaVenta> l = new ArrayList<>();
        l.add(new LineaVenta(gaseosa, 2000, 3000, 0, null));
        l.add(new LineaVenta(arroz, 1000, 2500, 0, null));
        l.add(new LineaVenta(azucar, 1500, 2200, 0, null));
        long total = 0;
        for (LineaVenta x : l) {
            total += x.getSubtotal();
        }
        List<Pago> pagos = List.of(new Pago(MetodoPago.EFECTIVO, total));
        Comprobante c = new Comprobante(a.nombre, 1, LocalDateTime.of(2026, 11, 3, 10, 35), "Administrador",
                "Consumidor final", l, total, pagos, 20000, 20000 - total);
        c.ajustes = a;
        return c;
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
