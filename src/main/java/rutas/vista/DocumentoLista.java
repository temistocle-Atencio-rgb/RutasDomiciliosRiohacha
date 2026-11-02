package rutas.vista;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.print.PageFormat;
import java.awt.print.Pageable;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.ImageIcon;
import rutas.logica.AjustesRecibo;

/**
 * Documento de una lista para imprimir o guardar como PDF (hoja carta): arriba el logo y los
 * datos del negocio, el titulo, y la tabla repartida en las paginas que haga falta.
 */
class DocumentoLista implements Printable, Pageable {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm a");
    private static final int ALTO_FILA = 18;
    private static final int ALTO_CABEZA = 118; // logo, datos y titulo
    private static final int ALTO_PIE = 24;

    private final String titulo;
    private final String[] columnas;
    private final float[] pesos;          // ancho relativo de cada columna
    private final boolean[] derecha;      // columnas alineadas a la derecha (numeros)
    private final List<String[]> filas;
    private final AjustesRecibo negocio;
    private final LocalDateTime cuando = LocalDateTime.now();
    private final PageFormat formato;
    private String subtitulo;              // una linea opcional debajo de la raya (proveedor, factura...)
    private String conteo;                 // "3 productos" en vez de contar las filas

    DocumentoLista(String titulo, String[] columnas, float[] pesos, boolean[] derecha, List<String[]> filas,
            AjustesRecibo negocio) {
        this.titulo = titulo;
        this.columnas = columnas;
        this.pesos = pesos;
        this.derecha = derecha;
        this.filas = filas;
        this.negocio = negocio;
        formato = new PageFormat();
        Paper carta = new Paper();
        carta.setSize(612, 792); // 8,5 x 11 pulgadas
        carta.setImageableArea(36, 36, 540, 720); // margen de media pulgada
        formato.setPaper(carta);
    }

    void setSubtitulo(String s) {
        subtitulo = s;
    }

    void setConteo(String s) {
        conteo = s;
    }

    private int filasPorPagina() {
        return (int) ((formato.getImageableHeight() - ALTO_CABEZA - ALTO_PIE - 22) / ALTO_FILA);
    }

    @Override
    public int getNumberOfPages() {
        return Math.max(1, (filas.size() + filasPorPagina() - 1) / filasPorPagina());
    }

    @Override
    public PageFormat getPageFormat(int pagina) {
        return formato;
    }

    @Override
    public Printable getPrintable(int pagina) {
        return this;
    }

    @Override
    public int print(Graphics g0, PageFormat pf, int pagina) {
        if (pagina >= getNumberOfPages()) {
            return NO_SUCH_PAGE;
        }
        Graphics2D g = (Graphics2D) g0;
        Tema.suavizar(g);
        g.translate(pf.getImageableX(), pf.getImageableY());
        dibujar(g, (int) pf.getImageableWidth(), (int) pf.getImageableHeight(), pagina);
        return PAGE_EXISTS;
    }

    /** Dibuja una pagina en (0,0) con el ancho y alto dados (en puntos). */
    void dibujar(Graphics2D g, int w, int h, int pagina) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        // ---- cabeza: logo a la izquierda, titulo en el centro, negocio a la derecha ----
        Image logo = null;
        if (negocio.logo != null && new java.io.File(negocio.logo).isFile()) {
            logo = new ImageIcon(negocio.logo).getImage();
        }
        if (logo != null && logo.getWidth(null) > 0) {
            double e = Math.min(120.0 / logo.getWidth(null), 64.0 / logo.getHeight(null));
            int lw = (int) (logo.getWidth(null) * e), lh = (int) (logo.getHeight(null) * e);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(logo, 0, (70 - lh) / 2, lw, lh, null);
        }
        g.setColor(Color.BLACK);
        Font fTitulo = Tema.textoFuerte(12.5f);
        g.setFont(fTitulo);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(titulo.toUpperCase(), (w - fm.stringWidth(titulo.toUpperCase())) / 2, 40);
        int xDer = w;
        int y = 18;
        String nombre = negocio.nombre.isBlank() ? "DomiRuta" : negocio.nombre;
        y = derecha(g, nombre.toUpperCase(), Tema.textoFuerte(10.5f), xDer, y);
        if (!negocio.nit.isBlank()) {
            y = derecha(g, "NIT: " + negocio.nit, Tema.texto(9), xDer, y + 13);
        }
        if (!negocio.direccion.isBlank()) {
            y = derecha(g, "Dirección: " + negocio.direccion, Tema.texto(9), xDer, y + 13);
        }
        if (!negocio.telefono.isBlank()) {
            derecha(g, "Tel. " + negocio.telefono, Tema.texto(9), xDer, y + 13);
        }
        g.setColor(new Color(0xC9CFD6));
        g.drawLine(0, 82, w, 82);
        g.setColor(new Color(0x5B6670));
        g.setFont(Tema.texto(8.5f));
        String gen = "Generado el " + cuando.format(FECHA).replace("AM", "a. m.").replace("PM", "p. m.")
                + "  ·  " + (conteo != null ? conteo : filas.size() + (filas.size() == 1 ? " registro" : " registros"));
        if (subtitulo != null && !subtitulo.isBlank()) {
            g.setColor(Color.BLACK);
            g.setFont(Tema.texto(9));
            g.drawString(subtitulo, 0, 96);
            g.setColor(new Color(0x5B6670));
            g.setFont(Tema.texto(8));
            g.drawString(gen, 0, 106);
        } else {
            g.drawString(gen, 0, 100);
        }

        // ---- tabla ----
        int top = ALTO_CABEZA - 10;
        float total = 0;
        for (float p : pesos) {
            total += p;
        }
        int[] xs = new int[columnas.length + 1];
        for (int i = 0; i < columnas.length; i++) {
            xs[i + 1] = xs[i] + Math.round(w * pesos[i] / total);
        }
        xs[columnas.length] = w;
        g.setColor(new Color(0xE9ECF0));
        g.fillRect(0, top, w, ALTO_FILA + 2);
        g.setColor(Color.BLACK);
        g.setFont(Tema.textoFuerte(9.5f));
        for (int i = 0; i < columnas.length; i++) {
            celda(g, columnas[i], xs[i], xs[i + 1], top + 14, false);
        }
        int desde = pagina * filasPorPagina();
        int hasta = Math.min(filas.size(), desde + filasPorPagina());
        g.setFont(Tema.texto(9));
        int fy = top + ALTO_FILA + 2;
        g.setStroke(new BasicStroke(0.5f));
        for (int r = desde; r < hasta; r++) {
            String[] f = filas.get(r);
            g.setColor(Color.BLACK);
            for (int i = 0; i < columnas.length && i < f.length; i++) {
                celda(g, f[i], xs[i], xs[i + 1], fy + 13, derecha[i]);
            }
            fy += ALTO_FILA;
            g.setColor(new Color(0xD9DEE4));
            g.drawLine(0, fy, w, fy);
        }
        g.setColor(new Color(0xC9CFD6));
        g.drawRect(0, top, w, fy - top);
        for (int i = 1; i < columnas.length; i++) {
            g.drawLine(xs[i], top, xs[i], fy);
        }
        // ---- pie ----
        g.setColor(new Color(0x5B6670));
        g.setFont(Tema.texto(8.5f));
        String pie = "Página " + (pagina + 1) + " de " + getNumberOfPages();
        fm = g.getFontMetrics();
        g.drawString(pie, w - fm.stringWidth(pie), h - 6);
        g.drawString("DomiRuta", 0, h - 6);
    }

    private static int derecha(Graphics2D g, String s, Font f, int x, int y) {
        g.setFont(f);
        g.drawString(s, x - g.getFontMetrics().stringWidth(s), y);
        return y;
    }

    /** Texto centrado en su columna (o a la derecha si es numero), recortado si no cabe. */
    private static void celda(Graphics2D g, String s, int x0, int x1, int y, boolean aLaDerecha) {
        if (s == null) {
            return;
        }
        FontMetrics fm = g.getFontMetrics();
        int ancho = x1 - x0 - 8;
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t) > ancho) {
            t = t.substring(0, t.length() - 1);
        }
        if (!t.equals(s)) {
            t = t.substring(0, Math.max(1, t.length() - 1)) + "…";
        }
        int x = aLaDerecha ? x1 - 4 - fm.stringWidth(t) : x0 + (x1 - x0 - fm.stringWidth(t)) / 2;
        g.drawString(t, x, y);
    }
}
