package rutas.vista;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import rutas.logica.AjustesRecibo;
import rutas.logica.Letras;
import rutas.logica.PdfImagen;
import rutas.logica.Tarifa;
import rutas.modelo.Cliente;
import rutas.modelo.LineaVenta;
import rutas.modelo.Pago;

/**
 * Recibo de venta en hoja carta (PDF del correo y "Guardar PDF"), segun el diseño de facturas del manual
 * de marca: negro y grises, lineas finas, logo y nombre arriba y el logo de marca de agua girado.
 * Dos versiones: una hoja (pocos productos) y varias hojas (inicio, continuacion y cierre con el resumen).
 * Es un comprobante de venta: no lleva CUFE ni QR de la DIAN y lo dice en el pie.
 */
final class ReciboCarta {

    static final int ANCHO = 612; // carta en puntos
    static final int ALTO = 792;
    private static final double NITIDEZ = 2.5;

    private static final Color TXT = new Color(0x1F2933);
    private static final Color GRIS = new Color(0x6B7280);
    private static final Color LINEA = new Color(0xD5D8DC);
    private static final int IZQ = 48;
    private static final int DER = 564;
    private static final int MITAD = 348; // columna del resumen y de los totales
    private static final int PAGO_X = 352;

    private final Comprobante comp;
    private final AjustesRecibo a;
    private final BufferedImage logo;     // el logo subido (null = el cubo de DomiRuta)
    private final BufferedImage logoGris; // el mismo en grises para la marca de agua

    private ReciboCarta(Comprobante comp) {
        this.comp = comp;
        this.a = comp.ajustes != null ? comp.ajustes : new AjustesRecibo();
        this.logo = leerLogo(a.logo);
        this.logoGris = logo == null ? null : gris(logo);
    }

    static byte[] pdf(Comprobante comp) throws IOException {
        return PdfImagen.crearPaginas(imagenes(comp), ANCHO, ALTO);
    }

    /** Solo el logo (el subido o el cubo en grises), 72 px de alto, para el encabezado del correo. */
    static byte[] logoPng(Comprobante comp) {
        ReciboCarta r = new ReciboCarta(comp);
        int alto = 72;
        int ancho = r.logo == null ? (int) Math.round(30 * alto / 34.6)
                : (int) Math.min(200, Math.round((double) alto * r.logo.getWidth() / r.logo.getHeight()));
        int altoReal = r.logo == null ? alto : (int) Math.round((double) ancho * r.logo.getHeight() / r.logo.getWidth());
        BufferedImage img = new BufferedImage(ancho, altoReal, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Tema.suavizar(g);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        if (r.logo != null) {
            g.drawImage(r.logo, 0, 0, ancho, altoReal, null);
        } else {
            g.scale(alto / 34.6, alto / 34.6);
            cubo(g);
        }
        g.dispose();
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    static List<BufferedImage> imagenes(Comprobante comp) {
        return new ReciboCarta(comp).hojas();
    }

    // ------------------------------------------------------------------
    //  Filas y reparto en hojas
    // ------------------------------------------------------------------

    private static final class Fila {
        final String[] c;            // #, codigo, (descripcion), cant, unitario, desc, total
        final List<String> desc;
        int alto;

        Fila(String[] c, List<String> desc) {
            this.c = c;
            this.desc = desc;
        }
    }

    // columnas: x de inicio (# codigo descripcion) y borde derecho (cant, unitario, desc, total)
    private static final int X_NUM = 48;
    private static final int X_COD = 72;
    private static final int X_DESC = 168;
    private static final int R_CANT = 375;
    private static final int R_UNIT = 447;
    private static final int R_DESC = 491;
    private static final int R_TOTAL = 564;

    private List<Fila> filas(FontMetrics fm) {
        List<Fila> f = new ArrayList<>();
        int i = 1;
        for (LineaVenta l : comp.lineas) {
            String cant = l.getProducto().getUnidad() == rutas.modelo.Unidad.UNIDAD
                    ? PanelProductos.cantidad(l.getCantidad()) : CuadroGranel.peso(l.getCantidad(), l.getProducto());
            String codigo = l.getProducto().getCodigo() == null ? "" : l.getProducto().getCodigo();
            f.add(new Fila(new String[]{String.valueOf(i++), codigo, "", cant, Comprobante.precioUnitario(l),
                porcentaje(l), Tarifa.pesos(l.getSubtotal())},
                    partir(l.getProducto().getNombre(), fm, R_CANT - X_DESC - 34)));
        }
        return f;
    }

    private static String porcentaje(LineaVenta l) {
        if (l.getDescuento() <= 0) {
            return "0%";
        }
        if (l.getPorcentajeGuardado() > 0) {
            return rutas.logica.Carrito.textoPorcentaje(l.getPorcentajeGuardado()) + "%";
        }
        long bruto = l.getBruto();
        return bruto <= 0 ? "0%" : Math.round(l.getDescuento() * 100.0 / bruto) + "%";
    }

    private List<BufferedImage> hojas() {
        Graphics2D m = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        m.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        List<Fila> filas = filas(m.getFontMetrics(Tema.texto(12)));
        int altoObs = 0;
        FontMetrics fmObs = m.getFontMetrics(Tema.texto(11));
        for (String o : observaciones()) {
            altoObs += 14 * partir(o, fmObs, MITAD - IZQ - 24).size();
        }
        m.dispose();
        if (!observaciones().isEmpty()) {
            altoObs += 26;
        }

        // ---- una sola hoja (version "pocos productos") ----
        int paso = filas.size() <= 3 ? 48 : 24;
        int y = 390;
        for (Fila f : filas) {
            f.alto = paso + 14 * (f.desc.size() - 1);
            y += f.alto;
        }
        int altoResumenUna = Math.max(118 + altoObs, 60 + 22 * filasTotales()) + 70;
        if (y + altoResumenUna <= 760) {
            return List.of(hojaUnica(filas));
        }

        // ---- varias hojas: inicio, continuacion(es) y cierre ----
        for (Fila f : filas) {
            f.alto = 24 + 14 * (f.desc.size() - 1);
        }
        int finInicio = 668;          // hasta donde caben filas en la primera hoja (abajo va "Continúa...")
        int finSeguidas = 700;
        int altoCierre = 206 + altoObs + 22 * (filasTotales() - 1);
        List<List<Fila>> reparto = new ArrayList<>();
        List<Fila> actual = new ArrayList<>();
        int yy = 279;
        int fin = finInicio;
        for (Fila f : filas) {
            if (yy + f.alto > fin) {
                reparto.add(actual);
                actual = new ArrayList<>();
                yy = 165;
                fin = finSeguidas;
            }
            actual.add(f);
            yy += f.alto;
        }
        reparto.add(actual);
        if (yy + altoCierre > 712) { // el resumen no cabe: va en otra hoja con los ultimos productos
            List<Fila> ultima = reparto.get(reparto.size() - 1);
            List<Fila> pasan = new ArrayList<>();
            while (pasan.size() < 3 && ultima.size() > 1) {
                pasan.add(0, ultima.remove(ultima.size() - 1));
            }
            reparto.add(pasan);
        }
        List<BufferedImage> imgs = new ArrayList<>();
        int desde = 1;
        for (int p = 0; p < reparto.size(); p++) {
            List<Fila> fs = reparto.get(p);
            imgs.add(hojaDeVarias(fs, p + 1, reparto.size(), desde, filas.size()));
            desde += fs.size();
        }
        return imgs;
    }

    private int filasTotales() {
        int n = 2; // subtotal y total
        if (descuentos() > 0) {
            n++;
        }
        if (comp.valorDomicilio > 0) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    //  Hojas
    // ------------------------------------------------------------------

    private Graphics2D nueva(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        Tema.suavizar(g);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(NITIDEZ, NITIDEZ);
        return g;
    }

    private static BufferedImage lienzo() {
        return new BufferedImage((int) (ANCHO * NITIDEZ), (int) (ALTO * NITIDEZ), BufferedImage.TYPE_INT_RGB);
    }

    /** Version de una hoja: encabezado, cliente y pago, productos, resumen y agradecimiento. */
    private BufferedImage hojaUnica(List<Fila> filas) {
        BufferedImage img = lienzo();
        Graphics2D g = nueva(img);
        marcaDeAgua(g, 0.08f);
        encabezado(g, 0);
        raya(g, IZQ, 189, DER, TXT, 0.75f);

        // cliente (izquierda) y pago (derecha)
        semi(g, "Cliente", 12, TXT, IZQ, 222);
        semi(g, "Pago", 12, TXT, PAGO_X, 222);
        int yc = 245;
        for (String[] d : datosCliente()) {
            yc = fila(g, d[0], d[1], yc);
        }
        bloquePagoArriba(g, 245);

        raya(g, IZQ, 355.75f, DER, TXT, 0.75f);
        encabezadoTabla(g, 376);
        raya(g, IZQ, 389.5f, DER, LINEA, 0.5f);
        int y = 389;
        int base = filas.size() <= 3 ? 29 : 16; // con pocos productos las filas son altas, como en el diseño
        for (Fila f : filas) {
            y = filaTabla(g, f, y, base);
        }
        int fin = resumen(g, y, true);
        raya(g, IZQ, fin, DER, TXT, 0.75f);
        gracias(g, fin + 23, true);
        g.dispose();
        return img;
    }

    /** Version de varias hojas. */
    private BufferedImage hojaDeVarias(List<Fila> filas, int hoja, int hojas, int desde, int total) {
        BufferedImage img = lienzo();
        Graphics2D g = nueva(img);
        marcaDeAgua(g, 0.065f);
        // franja de arriba
        g.setFont(Tema.texto(9));
        g.setColor(GRIS);
        derecha(g, "Pesos colombianos · COP", DER, 45);
        int yTabla;
        if (hoja == 1) {
            encabezado(g, 16);
            raya(g, IZQ, 186, DER, TXT, 0.75f);
            semi(g, "Cliente", 12, TXT, IZQ, 213);
            g.setFont(Tema.texto(12));
            g.setColor(TXT);
            g.drawString(recortar(clienteEnUnRenglon(), g.getFontMetrics(), DER - 105), 105, 213);
            yTabla = 244;
        } else {
            encabezadoCorto(g);
            raya(g, IZQ, 114, DER, TXT, 0.75f);
            yTabla = 130;
        }
        // "DETALLE DE LA COMPRA"  ·  "Productos 17–38 de 50"
        g.setFont(Tema.interSemi(9));
        g.setColor(TXT);
        espaciado(g, "DETALLE DE LA COMPRA", IZQ, yTabla, 0.3f);
        if (!filas.isEmpty()) {
            g.setFont(Tema.texto(10));
            g.setColor(GRIS);
            derecha(g, "Productos " + desde + "–" + (desde + filas.size() - 1) + " de " + total, DER, yTabla);
        }
        raya(g, IZQ, yTabla + 7, DER, LINEA, 0.5f);
        encabezadoTabla(g, yTabla + 25);
        raya(g, IZQ, yTabla + 34.5f, DER, LINEA, 0.5f);
        int y = (int) (yTabla + 34.5f);
        for (Fila f : filas) {
            y = filaTabla(g, f, y, 16);
        }
        if (hoja == 1 && hojas > 1) { // solo la primera hoja avisa donde sigue (como en el diseño)
            g.setFont(Tema.texto(10));
            g.setColor(GRIS);
            String t = "Continúa en la página " + (hoja + 1)
                    + (hoja + 1 < hojas ? " · El resumen y el pago se presentan en la página " + hojas + "." : ".");
            g.drawString(t, IZQ, y + 22);
        }
        if (hoja == hojas) {
            int fin = resumen(g, y, false);
            fin = pagoAbajo(g, fin + 24);
            gracias(g, fin + 4, false);
        }
        piePagina(g, hoja, hojas);
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------
    //  Partes
    // ------------------------------------------------------------------

    private String nombreNegocio() {
        return a.nombre != null && !a.nombre.isBlank() ? a.nombre : comp.negocio;
    }

    /** Encabezado completo: logo y nombre, datos del negocio; a la derecha el numero, fecha, hora y quien atendio. */
    private void encabezado(Graphics2D g, int dy) {
        marca(g, IZQ, 44.69 + dy, 1.0, logo, TXT);
        // datos del negocio
        int y = 103 + dy;
        int paso = dy == 0 ? 16 : 15;
        if (!vacio(a.razonSocial) && !a.razonSocial.equalsIgnoreCase(a.nombre)) {
            semi(g, a.razonSocial, 11, TXT, IZQ, y);
            y += paso;
        }
        if (!vacio(a.nit)) {
            normal(g, "NIT " + a.nit, 11, GRIS, IZQ, y);
            y += paso;
        }
        String dirTel = (vacio(a.direccion) ? "" : a.direccion)
                + (vacio(a.telefono) ? "" : (vacio(a.direccion) ? "" : " · ") + "Tel. " + a.telefono);
        if (!dirTel.isEmpty()) {
            semi(g, dirTel, 11, TXT, IZQ, y);
            y += paso;
        }
        if (!vacio(a.ciudad)) {
            normal(g, a.ciudad, 11, GRIS, IZQ, y);
            y += paso;
        }
        if (!vacio(a.regimen) && !"No aplica".equals(a.regimen)) {
            normal(g, a.regimen, 11, GRIS, IZQ, y);
        }

        // derecha
        g.setFont(Tema.interSemi(12));
        g.setColor(GRIS);
        derecha(g, "Recibo de venta", DER, 56.5f + dy);
        g.setFont(Tema.titulo(29));
        g.setColor(TXT);
        derecha(g, "N.º " + Comprobante.numeroTexto(comp.numero), DER, 92.5f + dy);
        String fecha = comp.fecha.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String hora = comp.fecha.format(DateTimeFormatter.ofPattern("h:mm a")).replace("AM", "a. m.")
                .replace("PM", "p. m.");
        if (dy == 0) {
            parDerecha(g, "Fecha", fecha, 117);
            parDerecha(g, "Hora", hora, 135);
            parDerecha(g, "Atendió", comp.atendio, 153);
        } else {
            g.setFont(Tema.texto(11.5f));
            g.setColor(TXT);
            derecha(g, "Fecha: " + fecha + "  ·  " + hora, DER, 123);
            g.setFont(Tema.texto(11));
            g.setColor(GRIS);
            derecha(g, "Atendió: " + comp.atendio, DER, 142);
        }
    }

    /** Hojas de continuacion: logo y nombre mas pequeños, cliente; a la derecha numero y fecha. */
    private void encabezadoCorto(Graphics2D g) {
        marca(g, IZQ, 59.5, 0.81, logo, TXT);
        normal(g, "Cliente: " + nombreCliente(), 10.5f, TXT, IZQ, 101.5f);
        g.setFont(Tema.texto(10));
        g.setColor(GRIS);
        derecha(g, "Recibo de venta · Continuación", DER, 67);
        g.setFont(Tema.textoFuerte(15));
        g.setColor(TXT);
        derecha(g, "N.º " + Comprobante.numeroTexto(comp.numero), DER, 87.5f);
        g.setFont(Tema.texto(9.5f));
        g.setColor(GRIS);
        derecha(g, "Fecha: " + comp.fecha.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), DER, 102);
    }

    /**
     * Logo (o el cubo) y el nombre del negocio. escala 1 = encabezado; la marca de agua usa la misma figura
     * agrandada y girada.
     */
    private void marca(Graphics2D g0, double x, double y, double escala, BufferedImage imagen, Color colorTexto) {
        Graphics2D g = (Graphics2D) g0.create();
        g.translate(x, y);
        g.scale(escala, escala);
        double anchoLogo;
        if (imagen != null) {
            double alto = 34.6;
            double ancho = Math.min(62, alto * imagen.getWidth() / imagen.getHeight());
            double altoReal = ancho * imagen.getHeight() / imagen.getWidth();
            g.drawImage(imagen, 0, (int) Math.round((alto - altoReal) / 2), (int) Math.round(ancho),
                    (int) Math.round(altoReal), null);
            anchoLogo = ancho;
        } else {
            cubo(g);
            anchoLogo = 30;
        }
        String nombre = nombreNegocio();
        float tam = 24;
        Font f = Tema.titulo(tam);
        double max = 300 - anchoLogo;
        while (g.getFontMetrics(f).stringWidth(nombre) > max && tam > 14) {
            tam -= 1;
            f = Tema.titulo(tam);
        }
        g.setFont(f);
        g.setColor(colorTexto);
        g.drawString(nombre, (float) (anchoLogo + 8), 27.8f);
        g.dispose();
    }

    /** El cubo de DomiRuta en grises (cara de arriba clara, izquierda media, derecha oscura): 30 x 34,6. */
    private static void cubo(Graphics2D g) {
        Path2D arriba = new Path2D.Double();
        arriba.moveTo(15, 0);
        arriba.lineTo(30, 8.654);
        arriba.lineTo(15, 17.308);
        arriba.lineTo(0, 8.654);
        arriba.closePath();
        Path2D izquierda = new Path2D.Double();
        izquierda.moveTo(0, 8.654);
        izquierda.lineTo(15, 17.308);
        izquierda.lineTo(15, 34.615);
        izquierda.lineTo(0, 25.962);
        izquierda.closePath();
        Path2D derecha = new Path2D.Double();
        derecha.moveTo(15, 17.308);
        derecha.lineTo(30, 8.654);
        derecha.lineTo(30, 25.962);
        derecha.lineTo(15, 34.615);
        derecha.closePath();
        g.setColor(new Color(0x868B94));
        g.fill(arriba);
        g.setColor(new Color(0x454C57));
        g.fill(izquierda);
        g.setColor(new Color(0x222B36));
        g.fill(derecha);
    }

    /** El logo y el nombre en grande, girados 32°, muy suaves detras de todo (igual que en el diseño). */
    private void marcaDeAgua(Graphics2D g0, float opacidad) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setComposite(AlphaComposite.SrcOver.derive(opacidad));
        AffineTransform t = new AffineTransform();
        t.translate(52.52, 509.81);
        t.rotate(Math.toRadians(-32));
        t.scale(3.56, 3.56);
        t.translate(0, -8.654); // la esquina izquierda del cubo queda en el punto de arriba
        g.transform(t);
        marca(g, 0, 0, 1.0, logoGris, new Color(0x222B36));
        g.dispose();
    }

    private void bloquePagoArriba(Graphics2D g, int y) {
        for (String[] p : lineasPago()) {
            normal(g, p[0], 12, GRIS, PAGO_X, y);
            g.setFont(Tema.texto(12));
            g.setColor(TXT);
            derecha(g, p[1], 545, y);
            y += 21;
        }
        y += 2;
        semi(g, estadoPago(), 12, TXT, PAGO_X, y);
        normal(g, "Saldo pendiente: " + Tarifa.pesos(saldoPendiente()), 12, TXT, PAGO_X, y + 23);
    }

    /** Hoja de cierre (varias hojas): pago a la izquierda y valores a la derecha. */
    private int pagoAbajo(Graphics2D g, int y) {
        semi(g, "Pago", 12, TXT, IZQ, y);
        normal(g, "Medio de pago: " + medioDePago(), 12, TXT, IZQ, y + 18);
        semi(g, estadoPago(), 12, TXT, IZQ, y + 38);
        int yr = y;
        long aplicado = 0;
        for (Pago p : comp.pagos) {
            aplicado += p.getValor();
        }
        List<String[]> filasDer = new ArrayList<>();
        if (!comp.contraEntrega) {
            filasDer.add(new String[]{"Pago aplicado", Tarifa.pesos(aplicado)});
            if (comp.recibido > 0) {
                filasDer.add(new String[]{"Recibido", Tarifa.pesos(comp.recibido)});
                filasDer.add(new String[]{"Vueltas", Tarifa.pesos(comp.vueltas)});
            }
        }
        for (String[] f : filasDer) {
            normal(g, f[0], 12, GRIS, MITAD, yr);
            g.setFont(Tema.texto(12));
            g.setColor(TXT);
            derecha(g, f[1], DER, yr);
            yr += 20;
        }
        semi(g, "Saldo pendiente", 12, TXT, MITAD, yr);
        g.setFont(Tema.interSemi(12));
        derecha(g, Tarifa.pesos(saldoPendiente()), DER, yr);
        return Math.max(y + 38, yr) + 18;
    }

    /** Subtotal, descuentos, domicilio y total a la derecha; valor en letras (y observaciones) a la izquierda. */
    private int resumen(Graphics2D g, int y, boolean unaHoja) {
        int base = y + 29;
        // derecha
        int yr = base;
        long bruto = 0;
        for (LineaVenta l : comp.lineas) {
            bruto += l.getBruto();
        }
        yr = valor(g, "Subtotal", Tarifa.pesos(bruto), yr);
        if (descuentos() > 0) {
            yr = valor(g, "Descuentos", "−" + Tarifa.pesos(descuentos()), yr);
        }
        if (comp.valorDomicilio > 0) {
            yr = valor(g, "Domicilio", Tarifa.pesos(comp.valorDomicilio), yr);
        }
        float rayaTotal = yr - 22 + 16.5f;
        raya(g, MITAD, rayaTotal, DER, TXT, 0.75f);
        semi(g, "Total", 12, TXT, MITAD, rayaTotal + 29);
        g.setFont(Tema.interNegrita(24));
        g.setColor(TXT);
        derecha(g, Tarifa.pesos(totalAPagar()), DER, rayaTotal + 35);
        float finDer = rayaTotal + 50;
        if (!unaHoja) {
            raya(g, MITAD, finDer, DER, TXT, 0.75f);
        }

        // izquierda
        int ancho = MITAD - IZQ - 24;
        semi(g, "Valor en letras", 12, TXT, IZQ, base);
        int yl = base + 21;
        FontMetrics fm = g.getFontMetrics(Tema.texto(12));
        for (String r : partir(Letras.pesos(totalAPagar()) + ".", fm, ancho)) {
            normal(g, r, 12, TXT, IZQ, yl);
            yl += 15;
        }
        normal(g, "Valores en pesos colombianos (COP)", 11, unaHoja ? TXT : GRIS, IZQ, yl + 5);
        yl += 5;
        if (!unaHoja) {
            normal(g, resumenCantidades(), 11, GRIS, IZQ, yl + 19);
            yl += 19;
        }
        List<String> obs = observaciones();
        if (!obs.isEmpty()) {
            yl += 24;
            semi(g, "Observaciones", 12, TXT, IZQ, yl);
            FontMetrics f11 = g.getFontMetrics(Tema.texto(11));
            for (String o : obs) {
                for (String r : partir(o, f11, ancho)) {
                    yl += 14;
                    normal(g, r, 11, TXT, IZQ, yl);
                }
            }
        }
        return (int) Math.max(finDer + (unaHoja ? 0.75f : 0), yl + 14);
    }

    private int valor(Graphics2D g, String etiqueta, String v, int y) {
        normal(g, etiqueta, 12, GRIS, MITAD, y);
        g.setFont(Tema.texto(12));
        g.setColor(TXT);
        derecha(g, v, DER, y);
        return y + 22;
    }

    private void gracias(Graphics2D g, int y, boolean conLegal) {
        String gr = vacio(a.gracias) || a.gracias.equalsIgnoreCase("GRACIAS POR SU COMPRA") ? "Gracias por su compra"
                : a.gracias;
        semi(g, gr, 12, TXT, IZQ, y);
        int yy = y + 20;
        if (!vacio(a.telefono)) {
            normal(g, "Consultas sobre tu compra: " + a.telefono, 12, TXT, IZQ, yy);
            yy += 17;
        }
        if (!vacio(a.pie)) {
            normal(g, recortar(a.pie, g.getFontMetrics(Tema.texto(11)), DER - IZQ), 11, GRIS, IZQ, yy);
            yy += 16;
        }
        if (conLegal) {
            normal(g, "Comprobante de venta. Este documento no es una factura electrónica ni la reemplaza.", 10, GRIS,
                    IZQ, yy);
        }
    }

    private void piePagina(Graphics2D g, int hoja, int hojas) {
        raya(g, IZQ, 716, DER, TXT, 0.75f);
        g.setFont(Tema.interSemi(9.5f));
        g.setColor(TXT);
        g.drawString(nombreNegocio() + " · Recibo de venta " + Comprobante.numeroTexto(comp.numero), IZQ, 731.5f);
        derecha(g, "Página " + hoja + " de " + hojas, DER, 731.5f);
        normal(g, "Comprobante de venta. Este documento no es una factura electrónica ni la reemplaza.", 10, GRIS, IZQ,
                749);
    }

    private void encabezadoTabla(Graphics2D g, float y) {
        g.setFont(Tema.texto(11));
        g.setColor(GRIS);
        g.drawString("#", X_NUM, y);
        g.drawString("Código", X_COD, y);
        g.drawString("Descripción", X_DESC, y);
        derecha(g, "Cant.", R_CANT, y);
        derecha(g, "Vr. unitario", R_UNIT, y);
        derecha(g, "Desc.", R_DESC, y);
        derecha(g, "Vr. total", R_TOTAL, y);
    }

    /** Una fila de la tabla con su linea gris abajo; devuelve donde termina. */
    private int filaTabla(Graphics2D g, Fila f, int y, int base) {
        g.setFont(Tema.texto(12));
        g.setColor(TXT);
        FontMetrics fm = g.getFontMetrics();
        float b = y + base;
        g.drawString(f.c[0], X_NUM, b);
        g.drawString(recortar(f.c[1], fm, X_DESC - X_COD - 10), X_COD, b);
        for (int i = 0; i < f.desc.size(); i++) {
            g.drawString(f.desc.get(i), X_DESC, b + 14 * i);
        }
        derecha(g, f.c[3], R_CANT, b);
        derecha(g, f.c[4], R_UNIT, b);
        derecha(g, f.c[5], R_DESC, b);
        derecha(g, f.c[6], R_TOTAL, b);
        int fin = y + f.alto;
        raya(g, IZQ, fin + 0.5f, DER, LINEA, 0.5f);
        return fin;
    }

    // ------------------------------------------------------------------
    //  Datos
    // ------------------------------------------------------------------

    private String nombreCliente() {
        return comp.cliente == null || comp.cliente.isBlank() ? "Consumidor final" : comp.cliente.trim();
    }

    private List<String[]> datosCliente() {
        List<String[]> d = new ArrayList<>();
        d.add(new String[]{"Nombre", nombreCliente()});
        Cliente c = comp.datosCliente;
        if (c != null) {
            if (!c.documentoCorto().isEmpty()) {
                d.add(new String[]{"Documento", c.documentoCorto()});
            }
            if (!c.getTelefono().isEmpty()) {
                d.add(new String[]{"Celular", c.getTelefono()});
            }
            if (!c.getDireccion().isEmpty()) {
                d.add(new String[]{"Dirección", c.getDireccion()});
            }
            if (!c.getCorreo().isEmpty()) {
                d.add(new String[]{"Correo", c.getCorreo()});
            }
        }
        return d;
    }

    private String clienteEnUnRenglon() {
        StringBuilder b = new StringBuilder(nombreCliente());
        Cliente c = comp.datosCliente;
        if (c != null) {
            for (String s : new String[]{c.documentoCorto(), c.getTelefono(), c.getDireccion()}) {
                if (!s.isEmpty()) {
                    b.append("  ·  ").append(s);
                }
            }
        }
        return b.toString();
    }

    /** Fila "Nombre   Marcela Luna" del cliente (el valor puede bajar de renglon). */
    private int fila(Graphics2D g, String etiqueta, String valor, int y) {
        normal(g, etiqueta, 12, GRIS, IZQ, y);
        g.setFont(Tema.texto(12));
        g.setColor(TXT);
        List<String> rs = partir(valor, g.getFontMetrics(), PAGO_X - 112 - 16);
        for (int i = 0; i < rs.size(); i++) {
            g.drawString(rs.get(i), 112, y + 15 * i);
        }
        return y + 21 + 15 * (rs.size() - 1);
    }

    private List<String[]> lineasPago() {
        List<String[]> l = new ArrayList<>();
        if (comp.contraEntrega) {
            l.add(new String[]{"Contra entrega", "Efectivo"});
            return l;
        }
        for (Pago p : comp.pagos) {
            if (p.getValor() > 0) {
                l.add(new String[]{p.getMetodo().getTexto(), Tarifa.pesos(p.getValor())});
            }
        }
        if (comp.recibido > 0) {
            l.add(new String[]{"Recibido", Tarifa.pesos(comp.recibido)});
            l.add(new String[]{"Vueltas", Tarifa.pesos(comp.vueltas)});
        }
        return l;
    }

    private String medioDePago() {
        if (comp.contraEntrega) {
            return "contra entrega (efectivo)";
        }
        List<String> m = new ArrayList<>();
        for (Pago p : comp.pagos) {
            if (p.getValor() > 0) {
                m.add(p.getMetodo().getTexto().toLowerCase());
            }
        }
        return m.isEmpty() ? "efectivo" : String.join(" y ", m);
    }

    private String estadoPago() {
        if (comp.contraEntrega) {
            return "POR COBRAR AL ENTREGAR";
        }
        return comp.valorDomicilio > 0 ? "PRODUCTOS PAGADOS" : "PAGADO";
    }

    private long totalAPagar() {
        return comp.total + comp.valorDomicilio;
    }

    /** Lo que falta por pagar: contra entrega, todo; si pago en la tienda, solo el domicilio. */
    private long saldoPendiente() {
        return comp.contraEntrega ? totalAPagar() : comp.valorDomicilio;
    }

    private long descuentos() {
        long d = 0;
        for (LineaVenta l : comp.lineas) {
            d += l.getDescuento();
        }
        return d;
    }

    private String resumenCantidades() {
        long unidades = 0;
        for (LineaVenta l : comp.lineas) {
            unidades += l.getProducto().getUnidad() == rutas.modelo.Unidad.UNIDAD ? Math.max(1, l.getCantidad() / 1000) : 1;
        }
        int n = comp.lineas.size();
        return n + (n == 1 ? " producto" : " productos distintos") + " · " + unidades
                + (unidades == 1 ? " unidad" : " unidades");
    }

    /** La nota de la venta y lo del domicilio que no esta ya en el cuadro del cliente. */
    private List<String> observaciones() {
        List<String> obs = new ArrayList<>();
        if (comp.nota != null && !comp.nota.isBlank()) {
            obs.add(comp.nota.trim());
        }
        if (comp.domicilio != null) {
            Cliente c = comp.datosCliente;
            for (String r : comp.domicilio) {
                String t = r.startsWith("*") ? r.substring(1) : r;
                boolean repetido = t.equals(nombreCliente()) || t.startsWith("Domicilio:")
                        || t.startsWith("Productos pagados") || t.startsWith("CONTRA ENTREGA")
                        || c != null && (t.equals(c.getDireccion()) || t.equals("Cel. " + c.getTelefono()));
                if (!repetido) {
                    obs.add(t);
                }
            }
        }
        return obs;
    }

    // ------------------------------------------------------------------
    //  Dibujo
    // ------------------------------------------------------------------

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }

    private static void semi(Graphics2D g, String t, float tam, Color c, float x, float y) {
        g.setFont(Tema.interSemi(tam));
        g.setColor(c);
        g.drawString(t, x, y);
    }

    private static void normal(Graphics2D g, String t, float tam, Color c, float x, float y) {
        g.setFont(Tema.texto(tam));
        g.setColor(c);
        g.drawString(t, x, y);
    }

    private static void derecha(Graphics2D g, String t, float xDer, float y) {
        g.drawString(t, xDer - g.getFontMetrics().stringWidth(t), y);
    }

    /** "Fecha   31/10/2026" pegado a la derecha: la etiqueta gris a la izquierda del valor. */
    private static void parDerecha(Graphics2D g, String etiqueta, String valor, float y) {
        g.setFont(Tema.interSemi(12));
        g.setColor(TXT);
        int w = g.getFontMetrics().stringWidth(valor);
        derecha(g, valor, DER, y);
        g.setFont(Tema.texto(12));
        g.setColor(GRIS);
        derecha(g, etiqueta, DER - w - 13, y);
    }

    private static void espaciado(Graphics2D g, String t, float x, float y, float extra) {
        for (char ch : t.toCharArray()) {
            String s = String.valueOf(ch);
            g.drawString(s, x, y);
            x += g.getFontMetrics().stringWidth(s) + extra;
        }
    }

    private static void raya(Graphics2D g, float x1, float y, float x2, Color c, float grosor) {
        g.setColor(c);
        g.fill(new java.awt.geom.Rectangle2D.Float(x1, y, x2 - x1, grosor));
    }

    static List<String> partir(String texto, FontMetrics fm, int ancho) {
        List<String> r = new ArrayList<>();
        StringBuilder linea = new StringBuilder();
        for (String p : (texto == null ? "" : texto.trim()).split("\\s+")) {
            String prueba = linea.length() == 0 ? p : linea + " " + p;
            if (fm.stringWidth(prueba) <= ancho || linea.length() == 0) {
                linea.setLength(0);
                linea.append(prueba);
            } else {
                r.add(linea.toString());
                linea.setLength(0);
                linea.append(p);
            }
        }
        r.add(linea.toString());
        return r;
    }

    private static String recortar(String t, FontMetrics fm, int ancho) {
        if (t == null) {
            return "";
        }
        if (fm.stringWidth(t) <= ancho) {
            return t;
        }
        String s = t;
        while (s.length() > 1 && fm.stringWidth(s + "…") > ancho) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    private static BufferedImage leerLogo(String ruta) {
        if (vacio(ruta)) {
            return null;
        }
        try {
            File f = new File(ruta);
            return f.isFile() ? javax.imageio.ImageIO.read(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** El logo en grises (respetando lo transparente) para la marca de agua. */
    private static BufferedImage gris(BufferedImage o) {
        BufferedImage r = new BufferedImage(o.getWidth(), o.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < o.getHeight(); y++) {
            for (int x = 0; x < o.getWidth(); x++) {
                int p = o.getRGB(x, y);
                int al = (p >>> 24) & 0xff;
                int l = (int) Math.round(0.299 * ((p >> 16) & 0xff) + 0.587 * ((p >> 8) & 0xff) + 0.114 * (p & 0xff));
                r.setRGB(x, y, (al << 24) | (l << 16) | (l << 8) | l);
            }
        }
        return r;
    }
}
