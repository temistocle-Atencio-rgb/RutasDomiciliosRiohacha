package rutas.vista;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
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
 */
public class Aviso extends javax.swing.JPanel {

    public enum Tipo { BIEN, AVISO, ERROR }

    private static final int ANCHO = 340;
    private static final int ALTO = 76;

    private final String titulo;
    private final String texto;
    private final Tipo tipo;
    private final long inicio = System.currentTimeMillis();
    private Timer timer;

    private Aviso(String titulo, String texto, Tipo tipo) {
        this.titulo = titulo;
        this.texto = texto;
        this.tipo = tipo;
        setOpaque(false);
        getAccessibleContext().setAccessibleName(titulo + ". " + texto);
    }

    /** Muestra el aviso en la esquina de abajo a la derecha del panel indicado. */
    public static void mostrar(JComponent panel, Tipo tipo, String titulo, String texto) {
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
        Aviso a = new Aviso(titulo, texto, tipo);
        Point p = SwingUtilities.convertPoint(panel, panel.getWidth() - ANCHO - 20, panel.getHeight() - ALTO - 20, lp);
        a.setBounds(p.x, p.y, ANCHO, ALTO);
        lp.add(a, JLayeredPane.PALETTE_LAYER);
        a.timer = new Timer(16, e -> {
            a.repaint();
            if (System.currentTimeMillis() - a.inicio > a.duracion()) {
                a.quitar();
            }
        });
        a.timer.start();
    }

    private long duracion() {
        return tipo == Tipo.BIEN ? 1500 : 3200; // los avisos y errores se leen con calma
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
        g.setFont(Tema.textoFuerte(14));
        g.setColor(Tema.GRIS_OSCURO);
        g.drawString(recortar(g.getFontMetrics(), titulo, ancho), tx, cy - 4);
        g.setFont(Tema.texto(13));
        g.setColor(tipo == Tipo.BIEN ? Tema.GRIS_TEXTO : c == Tema.ERROR ? Tema.ERROR_TEXTO : c);
        g.drawString(recortar(g.getFontMetrics(), texto, ancho), tx, cy + 15);
        g.dispose();
    }

    private static String recortar(FontMetrics fm, String s, int ancho) {
        if (s == null) {
            return "";
        }
        if (fm.stringWidth(s) <= ancho) {
            return s;
        }
        String r = s;
        while (r.length() > 1 && fm.stringWidth(r + "…") > ancho) {
            r = r.substring(0, r.length() - 1);
        }
        return r + "…";
    }
}
