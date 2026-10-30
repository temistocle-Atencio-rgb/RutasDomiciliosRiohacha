package rutas.vista;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Polygon;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;

/**
 * Isotipo (cubo isometrico: el paquete del domicilio) y logotipo "DomiRuta"
 * siguiendo las reglas del manual: tres rombos iguales a 30 grados,
 * nombre en Poppins SemiBold con espaciado de -2 %.
 */
public class Logo extends JComponent {

    public enum Version { COLOR, BLANCO }

    private final Version version;
    private final int alturaCubo;

    public Logo(Version version, int alturaCubo) {
        this.version = version;
        this.alturaCubo = alturaCubo;
        Font f = fuenteNombre();
        int ancho = (int) (alturaCubo * 0.866) + alturaCubo / 3
                + getFontMetrics(f).stringWidth("DomiRuta") + 4;
        setPreferredSize(new Dimension(ancho, alturaCubo + 4));
        setOpaque(false);
    }

    private Font fuenteNombre() {
        return Tema.subtitulo(alturaCubo * 0.62f).deriveFont(Map.of(TextAttribute.TRACKING, -0.02f));
    }

    /** Dibuja el cubo con la esquina superior izquierda en (x, y). */
    public static void dibujarCubo(Graphics2D g, double x, double y, double alto, Version v) {
        double ancho = alto * 0.866; // proporcion del hexagono isometrico
        double[][] p = {
            {0.5, 0}, {0, 0.25}, {1, 0.25}, {0.5, 0.5}, {0.5, 1}, {0, 0.75}, {1, 0.75}
        };
        int[][] xy = new int[7][2];
        for (int i = 0; i < 7; i++) {
            xy[i][0] = (int) Math.round(x + p[i][0] * ancho);
            xy[i][1] = (int) Math.round(y + p[i][1] * alto);
        }
        Color arriba, izquierda, derecha;
        if (v == Version.COLOR) {
            arriba = Tema.NARANJA;
            izquierda = Tema.AZUL;
            derecha = Tema.AZUL_PROFUNDO;
        } else {
            // version blanca del manual: tres caras blancas con opacidad 1, 0.8 y 0.6
            arriba = Color.WHITE;
            izquierda = new Color(255, 255, 255, 204);
            derecha = new Color(255, 255, 255, 153);
        }
        g.setColor(arriba);
        g.fillPolygon(poly(xy, 0, 2, 3, 1));
        g.setColor(izquierda);
        g.fillPolygon(poly(xy, 1, 3, 4, 5));
        g.setColor(derecha);
        g.fillPolygon(poly(xy, 3, 2, 6, 4));
    }

    /** Silueta del cubo (hexagono), para dibujarle un borde blanco encima del mapa. */
    public static Polygon silueta(double x, double y, double alto) {
        double ancho = alto * 0.866;
        double[][] p = {{0.5, 0}, {1, 0.25}, {1, 0.75}, {0.5, 1}, {0, 0.75}, {0, 0.25}};
        Polygon s = new Polygon();
        for (double[] q : p) {
            s.addPoint((int) Math.round(x + q[0] * ancho), (int) Math.round(y + q[1] * alto));
        }
        return s;
    }

    private static Polygon poly(int[][] xy, int... idx) {
        Polygon p = new Polygon();
        for (int i : idx) {
            p.addPoint(xy[i][0], xy[i][1]);
        }
        return p;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        dibujarCubo(g, 0, 2, alturaCubo, version);
        // logotipo a la derecha, centrado en altura (version horizontal principal)
        Font f = fuenteNombre();
        g.setFont(f);
        int x = (int) (alturaCubo * 0.866) + alturaCubo / 3;
        int y = 2 + alturaCubo / 2 + g.getFontMetrics().getAscent() / 2 - g.getFontMetrics().getDescent() / 3;
        g.setColor(version == Version.COLOR ? Tema.AZUL : Color.WHITE);
        g.drawString("Domi", x, y);
        x += g.getFontMetrics().stringWidth("Domi");
        g.setColor(version == Version.COLOR ? Tema.NARANJA : Color.WHITE);
        g.drawString("Ruta", x, y);
        g.dispose();
    }

    /** Iconos de la ventana (barra de tareas) con el cubo. */
    public static List<Image> iconos() {
        List<Image> lista = new ArrayList<>();
        for (int t : new int[]{16, 24, 32, 48, 64, 128}) {
            BufferedImage img = new BufferedImage(t, t, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            Tema.suavizar(g);
            double alto = t * 0.96;
            dibujarCubo(g, (t - alto * 0.866) / 2, (t - alto) / 2, alto, Version.COLOR);
            g.dispose();
            lista.add(img);
        }
        return lista;
    }
}
