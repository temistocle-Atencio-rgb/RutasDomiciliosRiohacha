package rutas.vista;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import rutas.logica.BaseTienda;
import rutas.logica.Ingreso;
import rutas.logica.Sesion;
import rutas.modelo.Rol;

/**
 * Pantalla de ingreso partida en dos: a la izquierda el panel azul de la marca con el
 * vendedor, a la derecha el formulario. La primera vez (sin usuarios) pide crear el
 * administrador. Enter entra; Esc cierra.
 */
public class DialogoIngreso extends JDialog {

    /** Version que se ve abajo del formulario (sirve cuando un cliente llama con un problema). */
    public static final String VERSION = "Versión 1.0 · 2026";
    private static final char PUNTO = '•';

    private final BaseTienda bd;
    private final Ingreso ingreso;
    private final boolean primeraVez;
    private final JTextField campoNombre = Tema.campo("Ej. Ana Gómez");
    private final JTextField campoUsuario = Tema.campo(null);
    private final JPasswordField campoClave = new JPasswordField();
    private final JPasswordField campoClave2 = new JPasswordField();
    private final JComponent avisoMayus = new AvisoMayus();
    private final Tema.Parrafo lblError = Tema.parrafo(" ", 340);
    private Sesion sesion;

    private DialogoIngreso(Frame dueno, BaseTienda bd, Ingreso ingreso) throws SQLException {
        super(dueno, "DomiRuta · Entrar", true);
        this.bd = bd;
        this.ingreso = ingreso;
        this.primeraVez = bd.contarUsuarios() == 0;
        setIconImages(Logo.iconos());

        JPanel raiz = new JPanel(new BorderLayout());
        raiz.add(new PanelMarca(), BorderLayout.WEST);
        raiz.add(formulario(), BorderLayout.CENTER);
        setContentPane(raiz);

        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                (primeraVez ? campoNombre : campoUsuario).requestFocusInWindow();
            }
        });
        // cabe en un portatil de 1366 x 768 con la escala de Windows al 125 %
        setSize(900, primeraVez ? 600 : 560);
        setResizable(false);
        setLocationRelativeTo(dueno);
    }

    // ------------------------------------------------------------------
    //  Formulario (lado derecho)
    // ------------------------------------------------------------------

    private JComponent formulario() {
        JPanel lado = new JPanel(new BorderLayout());
        lado.setBackground(Tema.BLANCO);

        JPanel caja = new JPanel();
        caja.setOpaque(false);
        caja.setLayout(new BoxLayout(caja, BoxLayout.Y_AXIS));
        caja.setPreferredSize(new Dimension(360, primeraVez ? 500 : 380));

        caja.add(Tema.tituloPantalla(primeraVez ? "Crear el administrador" : "Entrar a la tienda"));
        caja.add(Tema.espacio(4));
        Tema.Parrafo sub = Tema.parrafo(primeraVez
                ? "Es la primera vez que se abre la caja. El administrador ve todo y crea a los cajeros."
                : "Escriba su usuario y contraseña para empezar el turno.", 340);
        sub.setFont(Tema.texto(14));
        caja.add(sub);
        caja.add(Tema.espacio(16));

        if (primeraVez) {
            agregarCampo(caja, "Su nombre", campoNombre);
        }
        agregarCampo(caja, "Usuario", campoUsuario);
        agregarCampo(caja, "Contraseña", conOjo(campoClave, campoClave2));
        if (primeraVez) {
            agregarCampo(caja, "Repita la contraseña", conOjo(campoClave2, null));
        }
        avisoMayus.setVisible(false);
        caja.add(avisoMayus);
        lblError.setFont(Tema.texto(13));
        lblError.setForeground(Tema.ERROR_TEXTO);
        caja.add(lblError);
        caja.add(Tema.espacio(8));

        // el unico acento naranja de la pantalla: la accion principal
        Tema.Boton entrar = Tema.botonAccion(primeraVez ? "Crear y entrar" : "Entrar");
        entrar.setAlignmentX(LEFT_ALIGNMENT);
        entrar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        entrar.addActionListener(e -> aceptar());
        getRootPane().setDefaultButton(entrar);
        caja.add(entrar);

        JPanel centro = new JPanel(new GridBagLayout());
        centro.setOpaque(false);
        centro.add(caja);
        lado.add(centro, BorderLayout.CENTER);

        JLabel version = new JLabel("DomiRuta · " + VERSION);
        version.setFont(Tema.texto(12.5f));
        version.setForeground(Tema.GRIS_TEXTO);
        version.setHorizontalAlignment(JLabel.CENTER);
        version.setBorder(BorderFactory.createEmptyBorder(0, 0, 16, 0));
        lado.add(version, BorderLayout.SOUTH);
        return lado;
    }

    /** Etiqueta visible encima del campo (el texto gris de adentro desaparece al escribir). */
    private static void agregarCampo(JPanel caja, String etiqueta, JComponent campo) {
        JLabel l = Tema.etiqueta(etiqueta);
        JComponent real = campo instanceof CampoConOjo ? ((CampoConOjo) campo).clave : campo;
        l.setLabelFor(real);
        real.getAccessibleContext().setAccessibleName(etiqueta);
        caja.add(l);
        Tema.anchoCompleto(campo);
        campo.setMaximumSize(new Dimension(Integer.MAX_VALUE, campo.getPreferredSize().height));
        caja.add(campo);
        caja.add(Tema.espacio(12));
    }

    /** Campo de contraseña con el boton del ojo adentro; "pareja" cambia junto con este. */
    private JComponent conOjo(JPasswordField clave, JPasswordField pareja) {
        CampoConOjo c = new CampoConOjo(clave, pareja);
        KeyAdapter mayus = new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                revisarMayus();
            }
        };
        clave.addKeyListener(mayus);
        clave.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                revisarMayus();
            }
        });
        return c;
    }

    /** Muestra el aviso si Bloq Mayus esta activado (la causa mas comun de "contraseña incorrecta"). */
    private void revisarMayus() {
        boolean activo;
        try {
            activo = Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_CAPS_LOCK);
        } catch (UnsupportedOperationException e) {
            activo = false; // algunos sistemas no lo informan
        }
        if (avisoMayus.isVisible() != activo) {
            avisoMayus.setVisible(activo);
            revalidate();
        }
    }

    // ------------------------------------------------------------------
    //  Entrar
    // ------------------------------------------------------------------

    private void aceptar() {
        char[] clave = campoClave.getPassword();
        try {
            if (primeraVez) {
                char[] clave2 = campoClave2.getPassword();
                boolean iguales = Arrays.equals(clave, clave2);
                Arrays.fill(clave2, '\0');
                if (!iguales) {
                    error("Las contraseñas no coinciden");
                    return;
                }
                bd.crearUsuario(campoUsuario.getText(), campoNombre.getText(), Rol.ADMIN, clave);
            }
            sesion = ingreso.entrar(campoUsuario.getText(), clave);
            campoClave.setText("");
            campoClave2.setText("");
            dispose();
        } catch (IllegalArgumentException | Ingreso.IngresoException e) {
            error(e.getMessage());
        } catch (SQLException e) {
            error("No se pudo guardar: " + e.getMessage());
        } finally {
            Arrays.fill(clave, '\0');
        }
    }

    private void error(String mensaje) {
        lblError.setText(mensaje);
        campoClave.setText("");
        campoClave2.setText("");
        campoClave.requestFocusInWindow();
    }

    /** Muestra el ingreso y devuelve la sesion, o null si la persona cerro la ventana. */
    public static Sesion pedir(Frame dueno, BaseTienda bd, Ingreso ingreso) throws SQLException {
        DialogoIngreso d = new DialogoIngreso(dueno, bd, ingreso);
        d.setVisible(true);
        return d.sesion;
    }

    // ------------------------------------------------------------------
    //  Piezas
    // ------------------------------------------------------------------

    /** Panel azul de la marca: logo en blanco, la frase y el vendedor abajo. */
    private static class PanelMarca extends JPanel {
        private static final Color TEXTO_SUAVE = new Color(0xDCE6F2); // 6.3:1 sobre el azul
        private static final Image VENDEDOR = cargarImagen("/imagenes/vendedor.png");

        PanelMarca() {
            setBackground(Tema.AZUL);
            setPreferredSize(new Dimension(400, 560));
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(36, 36, 0, 36));
            Logo logo = new Logo(Logo.Version.BLANCO, 30);
            logo.setAlignmentX(LEFT_ALIGNMENT);
            logo.setMaximumSize(logo.getPreferredSize()); // que no se estire y empuje el texto hacia abajo
            add(logo);
            add(Box.createVerticalStrut(28));
            Tema.Parrafo frase = Tema.parrafo("Vende, cobra y despacha domicilios desde un solo lugar.", 320);
            frase.setFont(Tema.titulo(22));
            frase.setForeground(Color.WHITE);
            add(frase);
            add(Box.createVerticalStrut(8));
            Tema.Parrafo nota = Tema.parrafo("Cobre rápido, sepa qué le falta y entregue a tiempo.", 320);
            nota.setFont(Tema.texto(14));
            nota.setForeground(TEXTO_SUAVE);
            add(nota);
            add(Box.createVerticalGlue()); // el espacio de abajo queda para el vendedor
            getAccessibleContext().setAccessibleName("DomiRuta");
        }

        private static Image cargarImagen(String ruta) {
            try (InputStream in = DialogoIngreso.class.getResourceAsStream(ruta)) {
                return in == null ? null : ImageIO.read(in);
            } catch (Exception e) {
                return null; // sin foto el panel igual se ve bien
            }
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            // puntos de decoracion arriba a la derecha (blancos muy suaves)
            g.setColor(new Color(255, 255, 255, 46));
            for (int f = 0; f < 3; f++) {
                for (int c = 0; c < 6; c++) {
                    g.fillOval(getWidth() - 120 + c * 16, 34 + f * 16, 3, 3);
                }
            }
            if (VENDEDOR != null) {
                int alto = Math.max(160, getHeight() - 250); // debajo del texto
                int ancho = VENDEDOR.getWidth(null) * alto / VENDEDOR.getHeight(null);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(VENDEDOR, getWidth() - ancho + 10, getHeight() - alto, ancho, alto, null);
            }
            g.dispose();
        }
    }

    /** Campo de contraseña con el borde de los demas campos y un boton de ojo para verla. */
    private static class CampoConOjo extends JPanel {
        final JPasswordField clave;

        CampoConOjo(JPasswordField clave, JPasswordField pareja) {
            super(new BorderLayout());
            this.clave = clave;
            setBackground(Tema.BLANCO);
            setBorder(BorderFactory.createCompoundBorder(new Tema.BordeRedondo(Tema.BORDE_CONTROL, Tema.RADIO),
                    BorderFactory.createEmptyBorder(1, 1, 1, 4)));
            clave.setFont(Tema.texto(14));
            clave.setForeground(Tema.GRIS_OSCURO);
            clave.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 4));
            clave.setEchoChar(PUNTO);
            clave.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    repaint();
                }
            });
            add(clave, BorderLayout.CENTER);
            Ojo ojo = new Ojo();
            ojo.addActionListener(e -> {
                char eco = ojo.isSelected() ? (char) 0 : PUNTO;
                clave.setEchoChar(eco);
                if (pareja != null) {
                    pareja.setEchoChar(eco);
                }
                ojo.getAccessibleContext().setAccessibleName(ojo.isSelected() ? "Ocultar contraseña" : "Mostrar contraseña");
                ojo.setToolTipText(ojo.getAccessibleContext().getAccessibleName());
            });
            add(ojo, BorderLayout.EAST);
        }

        /** El borde se pone azul cuando el campo de adentro tiene el foco. */
        @Override
        public boolean hasFocus() {
            return clave.hasFocus();
        }
    }

    /** Boton del ojo: dibujado con linea de 1.8 px, sin cuadro detras (manual). */
    private static class Ojo extends JToggleButton {
        Ojo() {
            setPreferredSize(new Dimension(34, 30));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setToolTipText("Mostrar contraseña");
            getAccessibleContext().setAccessibleName("Mostrar contraseña");
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int cx = getWidth() / 2, cy = getHeight() / 2;
            if (isFocusOwner()) {
                g.setColor(Tema.AZUL);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 4, 4); // radio de 2 px (cuadro pequeño)
            }
            g.setColor(getModel().isRollover() ? Tema.GRIS_OSCURO : Tema.GRIS_TEXTO);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D ojo = new Path2D.Double();
            ojo.moveTo(cx - 9, cy);
            ojo.quadTo(cx, cy - 9, cx + 9, cy);
            ojo.quadTo(cx, cy + 9, cx - 9, cy);
            g.draw(ojo);
            g.drawOval(cx - 3, cy - 3, 6, 6);
            if (!isSelected()) { // tachado mientras la contraseña esta oculta
                g.drawLine(cx - 8, cy + 7, cx + 8, cy - 7);
            }
            g.dispose();
        }
    }

    /** Aviso de Bloq Mayus: texto de aviso sobre su fondo, con el radio del manual. */
    private static class AvisoMayus extends JLabel {
        AvisoMayus() {
            super("Bloq Mayús está activado");
            setAlignmentX(LEFT_ALIGNMENT);
            setFont(Tema.texto(13));
            setForeground(Tema.AVISO_TEXTO);
            setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(Tema.AVISO_FONDO);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), Tema.ARCO, Tema.ARCO);
            g.dispose();
            super.paintComponent(g0);
        }
    }
}
