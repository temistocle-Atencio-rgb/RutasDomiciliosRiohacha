package rutas.vista;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
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
    // estados: el color de marca para marcas y puntos, y uno mas oscuro cuando va como texto
    public static final Color EXITO = new Color(0x2E9E5B);
    public static final Color EXITO_TEXTO = new Color(0x1E7A44);
    public static final Color ERROR = new Color(0xD64545);
    public static final Color ERROR_TEXTO = new Color(0xB03030);
    public static final Color AVISO_TEXTO = new Color(0x8A4B00);
    public static final Color AVISO_FONDO = new Color(0xFDEBD3);
    // neutros del manual
    public static final Color GRIS_TEXTO = new Color(0x5B6670); // notas
    public static final Color BORDE = new Color(0xE3E7EC);      // lineas
    /** Borde de campos y botones pequenos: un paso mas fuerte que la linea para que se vean como control. */
    public static final Color BORDE_CONTROL = new Color(0x8592A3); // 3.2:1 sobre blanco, se distingue como campo
    public static final Color AZUL_SUAVE = new Color(0xE6EEF7);
    /** Texto de ayuda dentro de los campos: 4.7:1 sobre blanco. */
    public static final Color AYUDA = new Color(0x6B7480);
    /** Esquinas del manual: 5 px de radio (Java pide el diametro, por eso 10). */
    public static final int RADIO = 5;
    public static final int ARCO = RADIO * 2;
    /** En cuadros de menos de 60 px el radio es de 2 px. */
    public static final int ARCO_PEQUENO = 4;

    /** Arco segun el tamano del cuadro. */
    public static int arco(int ancho, int alto) {
        return ancho < 60 && alto < 60 ? ARCO_PEQUENO : ARCO;
    }

    /** Sombra suave del manual (0 2px 8px negro al 8 %), hecha con capas. */
    public static void sombra(Graphics2D g, int x, int y, int w, int h) {
        for (int i = 4; i >= 1; i--) {
            g.setColor(new Color(0, 0, 0, 5));
            g.fillRoundRect(x - i, y + 2 - i, w + i * 2, h + i * 2, ARCO + i * 2, ARCO + i * 2);
        }
    }

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
            setFont(textoFuerte(13.5f));
            setForeground(textoColor);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(9, 16, 9, 16)); // unos 38 px de alto
            // el anillo de foco se pinta a mano; hay que repintar al ganar o perder el foco
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    repaint();
                }
            });
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
            int arco = arco(getWidth(), getHeight());
            boolean foco = isFocusOwner();
            // con foco el relleno se encoge 3 px y queda un aro azul (o gris oscuro si el boton ya es azul)
            int m = foco ? 3 : 0;
            if (foco) {
                g.setColor(fondo == AZUL ? GRIS_OSCURO : AZUL);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arco, arco);
                g.setColor(BLANCO);
                g.fillRoundRect(2, 2, getWidth() - 5, getHeight() - 5, Math.max(2, arco - 2), Math.max(2, arco - 2));
            }
            g.setColor(f);
            g.fillRoundRect(m, m, getWidth() - 1 - m * 2, getHeight() - 1 - m * 2, arco, arco);
            if (borde != null && !foco) {
                g.setColor(isEnabled() ? borde : BORDE);
                g.setStroke(new BasicStroke(1.2f));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arco, arco);
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
        b.setFont(subtitulo(14.5f));
        b.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16)); // 40 px, el alto maximo del manual
        return b;
    }

    /** Boton secundario: blanco con borde y texto azul. */
    public static Boton botonSecundario(String texto) {
        return new Boton(texto, BLANCO, AZUL, AZUL);
    }

    public static Boton botonPequeno(String texto) {
        Boton b = new Boton(texto, BLANCO, AZUL, BORDE_CONTROL);
        b.setFont(textoFuerte(12.5f));
        b.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12)); // 32 px, el minimo del manual
        return b;
    }

    public static JTextField campo(String ayuda) {
        JTextField t = new CampoConAyuda(ayuda);
        t.setFont(texto(14));
        t.setForeground(GRIS_OSCURO);
        t.setBorder(BorderFactory.createCompoundBorder(new BordeRedondo(BORDE_CONTROL, RADIO),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        return t;
    }

    /** Campo de texto que muestra un texto de ayuda gris cuando esta vacio. */
    public static class CampoConAyuda extends JTextField {
        private final String ayuda;

        public CampoConAyuda(String ayuda) {
            this.ayuda = ayuda;
            // el texto de ayuda hace de etiqueta: los lectores de pantalla tambien lo leen
            if (ayuda != null) {
                getAccessibleContext().setAccessibleName(ayuda);
                setToolTipText(ayuda);
            }
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (getText().isEmpty() && ayuda != null) {
                Graphics2D g = (Graphics2D) g0.create();
                suavizar(g);
                g.setColor(AYUDA);
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
            boolean foco = c.hasFocus();
            g.setColor(foco ? AZUL : color);
            g.setStroke(new BasicStroke(foco ? 2f : 1f));
            g.drawRoundRect(x, y, w - 1, h - 1, radio * 2, radio * 2);
            g.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(1, 1, 1, 1);
        }
    }

    /** Tarjeta blanca con radio de 5 px y la sombra suave del manual, sobre el fondo gris claro. */
    public static class Tarjeta extends JPanel {
        private final Color fondo;
        static final int MARGEN = 5; // espacio alrededor para que se vea la sombra

        public Tarjeta() {
            this(BLANCO);
        }

        public Tarjeta(Color fondo) {
            this.fondo = fondo;
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(16 + MARGEN, 16 + MARGEN, 16 + MARGEN, 16 + MARGEN));
            setAlignmentX(LEFT_ALIGNMENT);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            suavizar(g);
            int w = getWidth() - MARGEN * 2, h = getHeight() - MARGEN * 2;
            sombra(g, MARGEN, MARGEN, w, h);
            g.setColor(fondo);
            g.fillRoundRect(MARGEN, MARGEN, w, h, ARCO, ARCO);
            g.dispose();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Titulo de pantalla: Poppins SemiBold de 21 px (el manual pide 20 a 24). */
    public static JLabel tituloPantalla(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(subtitulo(21));
        l.setForeground(GRIS_OSCURO);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        return anchoLibre(l);
    }

    /** Titulo de seccion del manual: 15 px en mayusculas con una linea naranja corta arriba. */
    public static JLabel seccion(String texto) {
        JLabel l = new JLabel(texto.toUpperCase()) {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setColor(NARANJA);
                g.fillRect(0, 0, 44, 3); // 44 x 3 px
                g.dispose();
                super.paintComponent(g0);
            }
        };
        l.setFont(subtitulo(15));
        l.setForeground(AZUL_PROFUNDO);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setVerticalAlignment(JLabel.BOTTOM);
        l.setBorder(BorderFactory.createEmptyBorder(11, 0, 8, 0)); // 3 de linea + 8 de aire arriba del texto
        return anchoLibre(l);
    }

    /** Etiqueta de un campo o de un grupo: Poppins, en frase normal (sin mayusculas sostenidas). */
    public static JLabel etiqueta(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(textoFuerte(13));
        l.setForeground(GRIS_OSCURO);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        return anchoLibre(l);
    }

    /**
     * En una columna, una etiqueta mide justo lo que Java calcula para su texto; con la escala
     * de Windows ese calculo queda corto por 1 o 2 px y se ve "RUTA CALCULAD...". Asi puede
     * usar todo el ancho de la tarjeta.
     */
    public static JLabel anchoLibre(JLabel l) {
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        return l;
    }

    public static JLabel textoNormal(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(texto(14));
        l.setForeground(GRIS_OSCURO);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Texto que se parte en varias lineas segun el ancho disponible. */
    public static Parrafo parrafo(String texto, int ancho) {
        return new Parrafo(texto, ancho);
    }

    /**
     * Parrafo de solo lectura hecho con JTextArea: ajusta el texto en varias lineas
     * y se ve como una etiqueta (sin borde ni fondo).
     */
    public static class Parrafo extends javax.swing.JTextArea {
        public Parrafo(String texto, int ancho) {
            super(texto);
            setLineWrap(true);
            setWrapStyleWord(true);
            setEditable(false);
            setFocusable(false);
            setOpaque(false);
            // sin esto, cada setText mueve el cursor invisible y el panel se desplaza solo hacia el texto
            ((javax.swing.text.DefaultCaret) getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
            // 6 px de holgura a la derecha: con la escala de Windows al 125 % el texto se dibuja
            // un poco mas ancho de lo que Java mide al partir las lineas, y se cortaba
            setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
            setFont(texto(12.5f)); // notas del manual
            setForeground(GRIS_TEXTO);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setSize(new Dimension(ancho, 10)); // ancho inicial para calcular el alto
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /**
     * Fila de una lista con un titulo y un detalle debajo, hecha con dos JTextArea
     * para que el texto largo baje de linea. Se usa en los renderizadores de las listas.
     */
    public static Component celda(javax.swing.JList<?> lista, String titulo, java.awt.Font fuenteTitulo,
                                  Color colorTitulo, String detalle, boolean seleccionada, Color fondo) {
        JPanel p = new JPanel(new java.awt.BorderLayout(0, 1));
        p.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        p.setBackground(seleccionada ? AZUL_SUAVE : fondo);
        int ancho = Math.max(120, (lista.getWidth() > 0 ? lista.getWidth() : 280) - 24); // menos el relleno de la celda
        Parrafo t = new Parrafo(titulo, ancho);
        t.setFont(fuenteTitulo);
        t.setForeground(colorTitulo);
        p.add(t, java.awt.BorderLayout.NORTH);
        if (detalle != null && !detalle.isEmpty()) {
            Parrafo d = new Parrafo(detalle, ancho);
            d.setFont(texto(12.5f));
            d.setForeground(GRIS_TEXTO);
            p.add(d, java.awt.BorderLayout.CENTER);
        }
        return p;
    }

    /** Icono de un circulo de color (para la leyenda de las motos). */
    public static javax.swing.Icon punto(Color color) {
        return new javax.swing.Icon() {
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                suavizar(g);
                g.setColor(color);
                g.fillOval(x, y + 1, 10, 10);
                g.dispose();
            }

            public int getIconWidth() { return 10; }
            public int getIconHeight() { return 12; }
        };
    }

    public static JCheckBox casilla(String texto) {
        JCheckBox c = new JCheckBox(texto);
        c.setFont(texto(14));
        c.setForeground(GRIS_OSCURO);
        c.setOpaque(false);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c; // se deja el foco del sistema para que se vea al usar el teclado
    }

    /** Nombre que leen los lectores de pantalla (para botones que solo tienen icono). */
    public static void nombreAccesible(JComponent c, String nombre) {
        c.getAccessibleContext().setAccessibleName(nombre);
        if (c.getToolTipText() == null) {
            c.setToolTipText(nombre);
        }
    }

    /**
     * Lista que, vacia, muestra una frase dentro de su marco en vez de un cuadro en blanco.
     */
    public static class Lista<T> extends javax.swing.JList<T> {
        private String vacia;

        public Lista(javax.swing.ListModel<T> modelo, String vacia) {
            super(modelo);
            this.vacia = vacia;
            // las filas parten el texto segun el ancho: si el ancho cambia, se vuelven a medir
            addComponentListener(new java.awt.event.ComponentAdapter() {
                private int ancho = -1;

                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (getWidth() != ancho) {
                        ancho = getWidth();
                        setFixedCellHeight(10);
                        setFixedCellHeight(-1);
                    }
                }
            });
        }

        public void setTextoVacia(String vacia) {
            this.vacia = vacia;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (getModel().getSize() == 0 && vacia != null) {
                Graphics2D g = (Graphics2D) g0.create();
                suavizar(g);
                g.setFont(texto(13));
                g.setColor(GRIS_TEXTO);
                FontMetrics fm = g.getFontMetrics();
                int ancho = Math.max(80, getWidth() - 28);
                int y = 14 + fm.getAscent();
                StringBuilder linea = new StringBuilder();
                for (String palabra : vacia.split(" ")) {
                    String prueba = linea.length() == 0 ? palabra : linea + " " + palabra;
                    if (fm.stringWidth(prueba) > ancho && linea.length() > 0) {
                        g.drawString(linea.toString(), 14, y);
                        y += fm.getHeight();
                        linea = new StringBuilder(palabra);
                    } else {
                        linea = new StringBuilder(prueba);
                    }
                }
                g.drawString(linea.toString(), 14, y);
                g.dispose();
            }
        }
    }

    /**
     * Fila de componentes con separacion. Si no caben a lo ancho, los que sobran bajan a la
     * siguiente linea (asi un boton nunca se sale de la tarjeta, con cualquier letra o escala).
     */
    public static JPanel fila(int espacio, JComponent... comps) {
        JPanel p = new JPanel(new FilaQueBaja(espacio));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JComponent c : comps) {
            p.add(c);
        }
        return p;
    }

    /** FlowLayout que calcula su alto segun el ancho disponible, para poder bajar de linea. */
    public static class FilaQueBaja extends java.awt.FlowLayout {
        public FilaQueBaja(int espacio) {
            super(LEFT, espacio, 0);
            setAlignOnBaseline(false);
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container destino) {
            return medir(destino, true);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container destino) {
            Dimension d = medir(destino, false);
            d.width -= getHgap() + 1;
            return d;
        }

        private Dimension medir(java.awt.Container destino, boolean preferido) {
            synchronized (destino.getTreeLock()) {
                // ancho disponible: el del padre (la tarjeta) menos sus margenes
                java.awt.Container padre = destino.getParent();
                int ancho = destino.getWidth();
                if (padre != null && padre.getWidth() > 0) {
                    java.awt.Insets ip = padre.getInsets();
                    ancho = padre.getWidth() - ip.left - ip.right;
                }
                if (ancho <= 0) {
                    ancho = Integer.MAX_VALUE;
                }
                java.awt.Insets in = destino.getInsets();
                int maximo = ancho - in.left - in.right;
                int x = 0, altoFila = 0, total = 0, anchoUsado = 0;
                for (Component c : destino.getComponents()) {
                    if (!c.isVisible()) {
                        continue;
                    }
                    Dimension d = preferido ? c.getPreferredSize() : c.getMinimumSize();
                    if (x > 0 && x + getHgap() + d.width > maximo) {
                        total += altoFila + espacioVertical;
                        anchoUsado = Math.max(anchoUsado, x);
                        x = 0;
                        altoFila = 0;
                    }
                    x += (x > 0 ? getHgap() : 0) + d.width;
                    altoFila = Math.max(altoFila, d.height);
                }
                total += altoFila;
                anchoUsado = Math.max(anchoUsado, x);
                return new Dimension(Math.min(anchoUsado, maximo) + in.left + in.right, total + in.top + in.bottom);
            }
        }

        private static final int espacioVertical = 6;

        @Override
        public void layoutContainer(java.awt.Container destino) {
            synchronized (destino.getTreeLock()) {
                java.awt.Insets in = destino.getInsets();
                int maximo = destino.getWidth() - in.left - in.right;
                int x = in.left, y = in.top, altoFila = 0;
                for (Component c : destino.getComponents()) {
                    if (!c.isVisible()) {
                        continue;
                    }
                    Dimension d = c.getPreferredSize();
                    if (x > in.left && x + d.width > in.left + maximo) {
                        x = in.left;
                        y += altoFila + espacioVertical;
                        altoFila = 0;
                    }
                    c.setBounds(x, y, Math.min(d.width, maximo), d.height);
                    x += d.width + getHgap();
                    altoFila = Math.max(altoFila, d.height);
                }
            }
        }
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
        return new BordeRedondo(BORDE_CONTROL, RADIO);
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
