package rutas.vista;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.Arc2D;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Aviso que no interrumpe: aparece abajo a la derecha de un panel, dibuja un circulo
 * que se completa y un chulito, y se va solo (1,5 s en total). No recibe clics.
 * Los importantes (algo que hay que hacer) duran 8 s, y ninguno se va mientras el mouse esté encima.
 * Lo que llega del domiciliario queda además en la campana de avisos (arriba).
 */
public class Aviso extends javax.swing.JPanel {

    public enum Tipo { BIEN, AVISO, ERROR, MENSAJE }

    private static final int ANCHO = 340;
    private static final int ALTO = 76;

    private final String titulo;
    private final String texto;
    private final Tipo tipo;
    private final boolean importante;
    private long inicio = System.currentTimeMillis();
    private Timer timer;

    private Aviso(String titulo, String texto, Tipo tipo, boolean importante) {
        this.titulo = titulo;
        this.texto = texto;
        this.tipo = tipo;
        this.importante = importante;
        setOpaque(false);
        getAccessibleContext().setAccessibleName(titulo + ". " + texto);
    }

    /** Muestra el aviso en la esquina de abajo a la derecha del panel indicado. */
    public static void mostrar(JComponent panel, Tipo tipo, String titulo, String texto) {
        mostrar(panel, tipo, titulo, texto, false);
    }

    /** importante = dura 8 s (no puede llevarla, debe plata, mensaje nuevo). */
    public static void mostrar(JComponent panel, Tipo tipo, String titulo, String texto, boolean importante) {
        JRootPane raiz = SwingUtilities.getRootPane(panel);
        if (raiz == null || !panel.isShowing()) {
            return;
        }
        JLayeredPane lp = raiz.getLayeredPane();
        for (java.awt.Component k : lp.getComponentsInLayer(JLayeredPane.PALETTE_LAYER)) {
            if (k instanceof Aviso) { // solo uno a la vez: el nuevo reemplaza al anterior
                ((Aviso) k).quitar();
            }
        }
        Aviso a = new Aviso(titulo, texto, tipo, importante);
        Point p = SwingUtilities.convertPoint(panel, panel.getWidth() - ANCHO - 20, panel.getHeight() - ALTO - 20, lp);
        a.setBounds(p.x, p.y, ANCHO, ALTO);
        lp.add(a, JLayeredPane.PALETTE_LAYER);
        a.timer = new Timer(16, e -> {
            long t = System.currentTimeMillis() - a.inicio;
            if (t > 400 && a.mouseEncima()) { // mientras lo lee no se va (queda entero)
                a.inicio = System.currentTimeMillis() - 400;
            }
            a.repaint();
            if (System.currentTimeMillis() - a.inicio > a.duracion()) {
                a.quitar();
            }
        });
        a.timer.start();
    }

    private long duracion() {
        if (importante) {
            return 8000;
        }
        return tipo == Tipo.BIEN ? 1500 : 3200; // los avisos y errores se leen con calma
    }

    private boolean mouseEncima() {
        try {
            java.awt.PointerInfo pi = java.awt.MouseInfo.getPointerInfo();
            if (pi == null || !isShowing()) {
                return false;
            }
            Point p = pi.getLocation();
            SwingUtilities.convertPointFromScreen(p, this);
            return p.x >= 0 && p.y >= 0 && p.x < getWidth() && p.y < getHeight();
        } catch (RuntimeException ex) { // sin mouse (por ejemplo, en las pruebas)
            return false;
        }
    }

    private void quitar() {
        if (timer != null) {
            timer.stop();
        }
        java.awt.Container padre = getParent();
        if (padre != null) {
            padre.remove(this);
            padre.repaint(getX(), getY(), getWidth(), getHeight());
        }
    }

    @Override
    public boolean contains(int x, int y) {
        return false; // los clics pasan a lo de abajo
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(ANCHO, ALTO);
    }

    private Color color() {
        switch (tipo) {
            case AVISO:
                return Tema.AVISO_TEXTO;
            case ERROR:
                return Tema.ERROR;
            case MENSAJE:
                return Tema.AZUL;
            default:
                return Tema.EXITO;
        }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        long t = System.currentTimeMillis() - inicio;
        long d = duracion();
        float alfa = t < 120 ? t / 120f : t > d - 300 ? Math.max(0f, (d - t) / 300f) : 1f;
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        g.setComposite(AlphaComposite.SrcOver.derive(alfa));
        int w = getWidth() - 10, h = getHeight() - 10;
        Tema.sombra(g, 5, 4, w, h);
        g.setColor(Tema.BLANCO);
        g.fillRoundRect(5, 4, w, h, Tema.ARCO, Tema.ARCO);

        // circulo que se dibuja en 400 ms y luego el chulito (o el signo)
        Color c = color();
        int cx = 38, cy = 4 + h / 2, r = 16;
        g.setColor(Tema.mezclar(c, Color.WHITE, 0.85));
        g.setStroke(new BasicStroke(3f));
        g.drawOval(cx - r, cy - r, r * 2, r * 2);
        float avance = Math.min(1f, t / 400f);
        g.setColor(c);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(cx - r, cy - r, r * 2, r * 2, 90, -360 * avance, Arc2D.OPEN));
        if (t > 380) {
            float p = Math.min(1f, (t - 380) / 160f);
            if (tipo == Tipo.BIEN) {
                int x1 = cx - 7, y1 = cy, x2 = cx - 2, y2 = cy + 6, x3 = cx + 8, y3 = cy - 6;
                if (p < 0.4f) {
                    float q = p / 0.4f;
                    g.drawLine(x1, y1, Math.round(x1 + (x2 - x1) * q), Math.round(y1 + (y2 - y1) * q));
                } else {
                    float q = (p - 0.4f) / 0.6f;
                    g.drawLine(x1, y1, x2, y2);
                    g.drawLine(x2, y2, Math.round(x2 + (x3 - x2) * q), Math.round(y2 + (y3 - y2) * q));
                }
            } else {
                g.drawLine(cx, cy - 8, cx, Math.round(cy - 8 + 10 * p));
                if (p >= 1f) {
                    g.fillOval(cx - 2, cy + 5, 4, 4);
                }
            }
        }

        int tx = 66, ancho = w - tx - 8;
        g.setColor(Tema.GRIS_OSCURO);
        java.awt.Font ft = Tema.textoFuerte(14), fx = Tema.texto(13);
        TextoEmoji.dibujar(g, TextoEmoji.recortar(titulo, ft, ancho), ft, tx, cy - 4);
        g.setColor(tipo == Tipo.BIEN || tipo == Tipo.MENSAJE ? Tema.GRIS_TEXTO : c == Tema.ERROR ? Tema.ERROR_TEXTO : c);
        TextoEmoji.dibujar(g, TextoEmoji.recortar(texto, fx, ancho), fx, tx, cy + 15); // el mensaje puede traer emojis
        g.dispose();
    }
}
