package rutas.vista;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.io.File;
import rutas.logica.CodigoQR;
import rutas.logica.EnlacesQR;

/**
 * Dibuja los codigos QR con el logo de la tienda en el centro: suelto (pantalla e imagen), en
 * un cartel tamaño carta para imprimir y en una imagen para estados de WhatsApp o Instagram.
 */
final class ImagenQR {

    /** Parte del lado del codigo que ocupa el cuadro del logo (el nivel H aguanta de sobra). */
    private static final double LOGO = 0.24;
    /** Margen blanco alrededor que pide la norma, en cuadritos. */
    static final int MARGEN = 4;

    private ImagenQR() {
    }

    /** El logo guardado en «Mi negocio»; null si no hay o no se puede leer. */
    static BufferedImage logo(String ruta) {
        if (ruta == null || ruta.isBlank()) {
            return null;
        }
        try {
            return javax.imageio.ImageIO.read(new File(ruta));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Dibuja el codigo (sin margen) en un cuadro de lado {@code tam} desde (x, y).
     * Los cuadritos van sin suavizado para que salgan nitidos en papel.
     */
    static void dibujar(Graphics2D g, CodigoQR q, double x, double y, double tam, Color color, BufferedImage logo) {
        Object antes = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        double m = tam / q.lado;
        // el cuadro del logo, en cuadritos enteros y centrado
        int n = logo == null ? 0 : (int) Math.round(q.lado * LOGO);
        if (n > 0 && (q.lado - n) % 2 != 0) {
            n++;
        }
        int desde = (q.lado - n) / 2, hasta = desde + n;
        g.setColor(color);
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        for (int fy = 0; fy < q.lado; fy++) {
            for (int fx = 0; fx < q.lado; fx++) {
                boolean bajoLogo = n > 0 && fx >= desde && fx < hasta && fy >= desde && fy < hasta;
                if (q.negro(fx, fy) && !bajoLogo) {
                    // un poquito mas grande para que no queden rayitas blancas entre cuadritos
                    p.append(new Rectangle2D.Double(x + fx * m, y + fy * m, m + 0.02, m + 0.02), false);
                }
            }
        }
        g.fill(p);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antes);
        if (n > 0) {
            double lx = x + desde * m, ly = y + desde * m, lt = n * m;
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Double(lx, ly, lt, lt, lt * 0.18, lt * 0.18));
            double pad = lt * 0.10, caja = lt - 2 * pad;
            double k = Math.min(caja / logo.getWidth(), caja / logo.getHeight());
            double w = logo.getWidth() * k, h = logo.getHeight() * k;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(logo, (int) Math.round(lx + (lt - w) / 2), (int) Math.round(ly + (lt - h) / 2),
                    (int) Math.round(w), (int) Math.round(h), null);
        }
    }

    /** Imagen cuadrada de {@code px} con el margen blanco incluido. */
    static BufferedImage imagen(String texto, BufferedImage logo, int px, Color color) {
        CodigoQR q = CodigoQR.de(texto);
        BufferedImage im = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, px, px);
        Tema.suavizar(g);
        double total = q.lado + 2 * MARGEN, m = px / total;
        dibujar(g, q, MARGEN * m, MARGEN * m, q.lado * m, color, logo);
        g.dispose();
        return im;
    }

    /** Lo que lleva un cartel. */
    static final class Datos {
        EnlacesQR.Tipo tipo;
        String enlace = "";
        String tienda = "";
        String pie = "";
        BufferedImage logo;
    }

    /**
     * Cartel: franja azul con el nombre de la tienda, el titular, el QR grande con el logo,
     * el enlace escrito (por si la camara no lo lee) y abajo el telefono y la direccion.
     */
    static void cartel(Graphics2D g, double w, double h, Datos d) {
        Tema.suavizar(g);
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Double(0, 0, w, h));
        double u = Math.min(w, h * 0.77) / 100; // una unidad de diseño
        // franja de arriba
        double franja = 16 * u;
        g.setColor(Tema.AZUL_PROFUNDO);
        g.fill(new Rectangle2D.Double(0, 0, w, franja));
        g.setColor(Tema.NARANJA);
        g.fill(new Rectangle2D.Double(0, franja, w, 0.9 * u));
        String nombre = d.tienda == null || d.tienda.isBlank() ? "Nuestra tienda" : d.tienda;
        centrado(g, nombre, Tema.titulo((float) (7 * u)), Color.WHITE, w, franja / 2, w - 12 * u);
        // titular
        double y = franja + 12 * u;
        centrado(g, d.tipo.titular, Tema.titulo((float) (8 * u)), Tema.AZUL_PROFUNDO, w, y, w - 12 * u);
        y += 7 * u;
        centrado(g, d.tipo.subtitulo, Tema.texto((float) (4 * u)), Tema.GRIS_TEXTO, w, y, w - 12 * u);
        // el QR en una tarjeta con borde
        double pieAlto = 16 * u;
        double libre = h - (y + 6 * u) - pieAlto;
        double lado = Math.min(w * 0.70, libre);
        double qx = (w - lado) / 2, qy = y + 6 * u;
        g.setColor(Color.WHITE);
        RoundRectangle2D tarjeta = new RoundRectangle2D.Double(qx, qy, lado, lado, 2 * u, 2 * u);
        g.fill(tarjeta);
        g.setColor(Tema.BORDE);
        g.setStroke(new BasicStroke((float) (0.4 * u)));
        g.draw(tarjeta);
        if (d.enlace != null && !d.enlace.isEmpty()) {
            CodigoQR q = CodigoQR.de(d.enlace);
            double dentro = lado * 0.88, m = (lado - dentro) / 2;
            dibujar(g, q, qx + m, qy + m, dentro, Tema.AZUL_PROFUNDO, d.logo);
        }
        // abajo: el enlace escrito y los datos
        double ya = qy + lado + 6 * u;
        String corto = d.enlace == null ? "" : d.enlace.replaceFirst("^https?://", "").replaceFirst("\\?.*$", "");
        centrado(g, corto, Tema.textoFuerte((float) (3.4 * u)), Tema.AZUL, w, ya, w - 12 * u);
        if (d.pie != null && !d.pie.isBlank()) {
            centrado(g, d.pie, Tema.texto((float) (3.4 * u)), Tema.GRIS_TEXTO, w, ya + 5.5 * u, w - 12 * u);
        }
    }

    /** Escribe centrado en x con el centro vertical en y; achica la letra si no cabe en {@code maximo}. */
    private static void centrado(Graphics2D g, String texto, Font f, Color c, double w, double y, double maximo) {
        if (texto == null || texto.isEmpty()) {
            return;
        }
        FontMetrics fm = g.getFontMetrics(f);
        while (fm.stringWidth(texto) > maximo && f.getSize2D() > 4) {
            f = f.deriveFont(f.getSize2D() * 0.94f);
            fm = g.getFontMetrics(f);
        }
        g.setFont(f);
        g.setColor(c);
        float base = (float) (y + (fm.getAscent() - fm.getDescent()) / 2.0);
        g.drawString(texto, (float) ((w - fm.stringWidth(texto)) / 2.0), base);
    }

    /** El cartel para imprimir en una hoja (carta o la que tenga la impresora). */
    static Printable imprimible(Datos d) {
        return (Graphics g0, PageFormat pf, int pagina) -> {
            if (pagina > 0) {
                return Printable.NO_SUCH_PAGE;
            }
            Graphics2D g = (Graphics2D) g0;
            g.translate(pf.getImageableX(), pf.getImageableY());
            cartel(g, pf.getImageableWidth(), pf.getImageableHeight(), d);
            return Printable.PAGE_EXISTS;
        };
    }

    /** El cartel como imagen vertical 1080 x 1350 (estados de WhatsApp, Instagram). */
    static BufferedImage imagenCartel(Datos d) {
        BufferedImage im = new BufferedImage(1080, 1350, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        cartel(g, 1080, 1350, d);
        g.dispose();
        return im;
    }
}
