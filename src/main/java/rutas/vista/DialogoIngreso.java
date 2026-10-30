package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.event.KeyEvent;
import java.sql.SQLException;
import java.util.Arrays;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import rutas.logica.BaseTienda;
import rutas.logica.Ingreso;
import rutas.logica.Sesion;
import rutas.modelo.Rol;

/**
 * Pantalla de ingreso. La primera vez (sin usuarios) pide crear el administrador.
 * Enter entra; Esc cierra.
 */
public class DialogoIngreso extends JDialog {

    private final BaseTienda bd;
    private final Ingreso ingreso;
    private final boolean primeraVez;
    private final JTextField campoNombre = Tema.campo("Su nombre");
    private final JTextField campoUsuario = Tema.campo("Usuario");
    private final JPasswordField campoClave = Tema.campoClave("Contraseña");
    private final JPasswordField campoClave2 = Tema.campoClave("Repita la contraseña");
    private final JLabel lblError = new JLabel(" ");
    private Sesion sesion;

    private DialogoIngreso(Frame dueno, BaseTienda bd, Ingreso ingreso) throws SQLException {
        super(dueno, "DomiRuta · Entrar", true);
        this.bd = bd;
        this.ingreso = ingreso;
        this.primeraVez = bd.contarUsuarios() == 0;
        setIconImages(Logo.iconos());

        JPanel fondo = new JPanel(new java.awt.GridBagLayout());
        fondo.setBackground(Tema.GRIS_CLARO);
        fondo.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));

        Tema.Tarjeta t = new Tema.Tarjeta();
        Logo logo = new Logo(Logo.Version.COLOR, 30);
        logo.setAlignmentX(LEFT_ALIGNMENT);
        t.add(logo);
        t.add(Tema.espacio(18));
        t.add(Tema.tituloPantalla(primeraVez ? "Crear el administrador" : "Entrar a la tienda"));
        t.add(Tema.espacio(4));
        t.add(Tema.parrafo(primeraVez
                ? "Es la primera vez que se abre la caja. El administrador ve todo y crea a los cajeros."
                : "Escriba su usuario y contraseña.", 330));
        t.add(Tema.espacio(12));
        JComponent[] campos = primeraVez
                ? new JComponent[]{campoNombre, campoUsuario, campoClave, campoClave2}
                : new JComponent[]{campoUsuario, campoClave};
        for (JComponent c : campos) {
            Tema.anchoCompleto(c);
            t.add(c);
            t.add(Tema.espacio(8));
        }
        lblError.setFont(Tema.texto(13));
        lblError.setForeground(Tema.ERROR_TEXTO);
        lblError.setAlignmentX(LEFT_ALIGNMENT);
        t.add(lblError);
        t.add(Tema.espacio(8));
        Tema.Boton entrar = Tema.botonPrimario(primeraVez ? "Crear y entrar" : "Entrar");
        entrar.addActionListener(e -> aceptar());
        t.add(Tema.fila(8, entrar));
        fondo.add(t);
        setContentPane(fondo);

        getRootPane().setDefaultButton(entrar);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setResizable(false);
        setLocationRelativeTo(dueno);
    }

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
}
