package rutas.vista;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;

/**
 * Colores, tipografias y componentes con el estilo del manual de marca:
 * azul como color principal, naranja solo para acentos y llamadas a la accion,
 * Poppins para titulos e Inter para textos.
 */
public final class Tema {

    // paleta del manual
    public static final Color AZUL = new Color(0x005198);
    public static final Color AZUL_PROFUNDO = new Color(0x003A73);
    public static final Color NARANJA = new Color(0xF28C1B);
    public static final Color BLANCO = Color.WHITE;
    public static final Color GRIS_CLARO = new Color(0xF2F4F7);
    public static final Color GRIS_OSCURO = new Color(0x1F2933);
    public static final Color EXITO = new Color(0x2E9E5B);
    public static final Color ERROR = new Color(0xD64545);
    // neutros de apoyo
    public static final Color GRIS_TEXTO = new Color(0x5B6675);
    public static final Color BORDE = new Color(0xD5DBE3);
    public static final Color AZUL_SUAVE = new Color(0xE6EEF7);

    private static final Map<String, Font> BASE = new HashMap<>();

    private Tema() {
    }

    /** Carga Poppins e Inter. Si no estan, se usa la fuente del sistema. */
    public static void cargarFuentes() {
        cargar("poppins-bold", "Poppins-Bold.ttf");
        cargar("poppins-semibold", "Poppins-SemiBold.ttf");
        cargar("inter", "Inter.ttf");
    }

    private static void cargar(String clave, String archivo) {
        try (InputStream in = abrir(archivo)) {
            if (in == null) {
                return;
            }
            Font f = Font.createFont(Font.TRUETYPE_FONT, in);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
            BASE.put(clave, f);
        } catch (IOException | FontFormatException e) {
            // se queda con la fuente por defecto
        }
    }

    private static InputStream abrir(String archivo) throws IOException {
        InputStream in = Tema.class.getResourceAsStream("/fuentes/" + archivo);
        if (in != null) {
            return in;
        }
        File f = new File("src/main/resources/fuentes/" + archivo);
        return f.exists() ? new FileInputStream(f) : null;
    }

    private static Font fuente(String clave, int estiloRespaldo, float tamano) {
        Font f = BASE.get(clave);
        if (f == null) {
            return new Font("SansSerif", estiloRespaldo, Math.round(tamano));
        }
        return f.deriveFont(tamano);
    }

    public static Font titulo(float tamano) {
        return fuente("poppins-bold", Font.BOLD, tamano);
    }

    public static Font subtitulo(float tamano) {
        return fuente("poppins-semibold", Font.BOLD, tamano);
    }

    public static Font texto(float tamano) {
        return fuente("inter", Font.PLAIN, tamano);
    }

    public static Font textoFuerte(float tamano) {
        return fuente("poppins-semibold", Font.BOLD, tamano);
    }

    public static void suavizar(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    // ------------------------------------------------------------------
    //  Componentes
    // ------------------------------------------------------------------

    /** Boton redondeado pintado a mano. */
    public static class Boton extends JButton {
        private final Color fondo;
        private final Color textoColor;
        private final Color borde;

        public Boton(String texto, Color fondo, Color textoColor, Color borde) {
            super(texto);
            this.fondo = fondo;
            this.textoColor = textoColor;
            this.borde = borde;
            setFont(textoFuerte(13));
            setForeground(textoColor);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            suavizar(g);
            Color f = fondo;
            if (!isEnabled()) {
                f = GRIS_CLARO;
            } else if (getModel().isPressed()) {
                f = fondo.darker();
            } else if (getModel().isRollover() || isSelected()) {
                f = mezclar(fondo, fondo == BLANCO ? AZUL_SUAVE : Color.BLACK, fondo == BLANCO ? 1 : 0.12);
            }
            g.setColor(f);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            if (borde != null) {
                g.setColor(isEnabled() ? borde : BORDE);
                g.setStroke(new BasicStroke(1.2f));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            }
            g.dispose();
            setForeground(isEnabled() ? textoColor : GRIS_TEXTO);
            super.paintComponent(g0);
        }
    }

    /** Boton principal: azul con texto blanco. */
    public static Boton botonPrimario(String texto) {
        return new Boton(texto, AZUL, BLANCO, null);
    }

    /** Llamada a la accion: naranja con texto gris oscuro (el blanco no da contraste sobre naranja). */
    public static Boton botonAccion(String texto) {
        Boton b = new Boton(texto, NARANJA, GRIS_OSCURO, null);
        b.setFont(titulo(14));
        b.setBorder(BorderFactory.createEmptyBorder(11, 14, 11, 14));
        return b;
    }

    /** Boton secundario: blanco con borde y texto azul. */
    public static Boton botonSecundario(String texto) {
        return new Boton(texto, BLANCO, AZUL, AZUL);
    }

    public static Boton botonPequeno(String texto) {
        Boton b = new Boton(texto, BLANCO, AZUL, BORDE);
        b.setFont(textoFuerte(11.5f));
        b.setBorder(BorderFactory.createEmptyBorder(4, 9, 4, 9));
        return b;
    }

    public static JTextField campo(String ayuda) {
        JTextField t = new CampoConAyuda(ayuda);
        t.setFont(texto(13));
        t.setForeground(GRIS_OSCURO);
        t.setBorder(BorderFactory.createCompoundBorder(new BordeRedondo(BORDE, 6),
                BorderFactory.createEmptyBorder(7, 9, 7, 9)));
        return t;
    }

    /** Campo de texto que muestra un texto de ayuda gris cuando esta vacio. */
    public static class CampoConAyuda extends JTextField {
        private final String ayuda;

        public CampoConAyuda(String ayuda) {
            this.ayuda = ayuda;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (getText().isEmpty() && ayuda != null) {
                Graphics2D g = (Graphics2D) g0.create();
                suavizar(g);
                g.setColor(new Color(0x9AA5B1));
                g.setFont(getFont());
                Insets in = getInsets();
                g.drawString(ayuda, in.left, in.top + g.getFontMetrics().getAscent());
                g.dispose();
            }
        }
    }

    public static class BordeRedondo extends AbstractBorder {
        private final Color color;
        private final int radio;

        public BordeRedondo(Color color, int radio) {
            this.color = color;
            this.radio = radio;
        }

        @Override
        public void paintBorder(Component c, Graphics g0, int x, int y, int w, int h) {
            Graphics2D g = (Graphics2D) g0.create();
            suavizar(g);
            g.setColor(c.hasFocus() ? AZUL : color);
            g.drawRoundRect(x, y, w - 1, h - 1, radio * 2, radio * 2);
            g.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(1, 1, 1, 1);
        }
    }

    /** Tarjeta gris clara con esquinas redondeadas, como las del manual. */
    public static class Tarjeta extends JPanel {
        private final Color fondo;

        public Tarjeta() {
            this(GRIS_CLARO);
        }

        public Tarjeta(Color fondo) {
            this.fondo = fondo;
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));
            setAlignmentX(LEFT_ALIGNMENT);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            suavizar(g);
            g.setColor(fondo);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g.dispose();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Titulo de seccion: Poppins SemiBold en azul profundo. */
    public static JLabel tituloSeccion(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(subtitulo(15));
        l.setForeground(AZUL_PROFUNDO);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        return l;
    }

    /** Etiqueta pequena en mayusculas (estilo "CODIGO DEL LOTE" del manual). */
    public static JLabel etiqueta(String texto) {
        JLabel l = new JLabel(texto.toUpperCase());
        l.setFont(textoFuerte(10.5f));
        l.setForeground(GRIS_TEXTO);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
        return l;
    }

    public static JLabel textoNormal(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(texto(12.5f));
        l.setForeground(GRIS_OSCURO);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Texto que se parte en varias lineas segun el ancho. */
    public static JLabel parrafo(String texto, int ancho) {
        JLabel l = new JLabel("<html><div style='width:" + ancho + "px'>" + texto + "</div></html>");
        l.setFont(texto(12));
        l.setForeground(GRIS_TEXTO);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    public static JCheckBox casilla(String texto) {
        JCheckBox c = new JCheckBox(texto);
        c.setFont(texto(12.5f));
        c.setForeground(GRIS_OSCURO);
        c.setOpaque(false);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setFocusPainted(false);
        return c;
    }

    /** Fila horizontal de componentes con separacion. */
    public static JPanel fila(int espacio, JComponent... comps) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (int i = 0; i < comps.length; i++) {
            if (i > 0) {
                p.add(Box.createHorizontalStrut(espacio));
            }
            p.add(comps[i]);
        }
        return p;
    }

    public static Component espacio(int alto) {
        return Box.createVerticalStrut(alto);
    }

    public static void anchoCompleto(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
    }

    public static JScrollPane scrollSinBorde(JComponent c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(BorderFactory.createEmptyBorder());
        s.getVerticalScrollBar().setUnitIncrement(16);
        s.setAlignmentX(Component.LEFT_ALIGNMENT);
        return s;
    }

    public static Border bordeLista() {
        return new BordeRedondo(BORDE, 6);
    }

    public static void estiloToggle(AbstractButton b) {
        b.setFocusPainted(false);
    }

    public static Color mezclar(Color a, Color b, double t) {
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    public static Color conAlfa(Color c, int alfa) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alfa);
    }
}
