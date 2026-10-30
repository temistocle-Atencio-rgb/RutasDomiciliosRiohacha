package rutas.vista;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.sql.SQLException;
import java.util.Arrays;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import rutas.logica.BaseTienda;
import rutas.modelo.Rol;
import rutas.modelo.Usuario;

/** Usuarios de la tienda (solo el administrador): crear cajeros, cambiar contraseñas, desactivar. */
public class PanelUsuarios extends JPanel {

    private final Aplicacion app;
    private final BaseTienda bd;
    private final DefaultListModel<Usuario> modelo = new DefaultListModel<>();
    private final JList<Usuario> lista = new Tema.Lista<>(modelo, "Aún no hay usuarios.");
    private final Tema.Boton btnClave = Tema.botonSecundario("Cambiar contraseña");
    private final Tema.Boton btnActivo = Tema.botonPequeno("Desactivar");
    private final Tema.Parrafo lblLista = mensaje();

    private final JTextField campoNombre = Tema.campo("Nombre completo");
    private final JTextField campoUsuario = Tema.campo("Usuario para entrar (ej. luis)");
    private final JComboBox<Rol> comboRol = new JComboBox<>(new Rol[]{Rol.CAJERO, Rol.ADMIN});
    private final JPasswordField campoClave = Tema.campoClave("Contraseña (mínimo 6)");
    private final JPasswordField campoClave2 = Tema.campoClave("Repita la contraseña");
    private final Tema.Parrafo lblForm = mensaje();

    public PanelUsuarios(Aplicacion app) {
        this.app = app;
        this.bd = app.getTienda();
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 20, 16));

        add(Tema.tituloPantalla("Usuarios"));
        add(Tema.parrafo("El administrador ve todo. Los cajeros venden y organizan los domicilios, "
                + "pero no ven precios de compra, ganancias ni usuarios.", 272));
        add(Tema.espacio(10));

        Tema.Tarjeta lst = new Tema.Tarjeta();
        lista.setCellRenderer(new Render());
        lista.getAccessibleContext().setAccessibleName("Lista de usuarios");
        lista.addListSelectionListener(e -> actualizarBotones());
        JScrollPane sc = new JScrollPane(lista);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(Tema.bordeLista());
        sc.setAlignmentX(LEFT_ALIGNMENT);
        sc.setPreferredSize(new Dimension(300, 180));
        sc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        lst.add(sc);
        lst.add(Tema.espacio(8));
        btnClave.addActionListener(e -> cambiarClave());
        btnActivo.addActionListener(e -> cambiarActivo());
        lst.add(Tema.fila(8, btnClave, btnActivo));
        lst.add(Tema.espacio(4));
        lst.add(lblLista);
        add(lst);
        add(Tema.espacio(12));

        Tema.Tarjeta form = new Tema.Tarjeta();
        form.add(Tema.seccion("Nuevo usuario"));
        Tema.estiloCombo(comboRol, Rol::getTexto);
        comboRol.getAccessibleContext().setAccessibleName("Rol");
        comboRol.setToolTipText("Rol");
        for (Component c : new Component[]{campoNombre, campoUsuario, comboRol, campoClave, campoClave2}) {
            Tema.anchoCompleto((javax.swing.JComponent) c);
            form.add(c);
            form.add(Tema.espacio(6));
        }
        form.add(lblForm);
        form.add(Tema.espacio(4));
        Tema.Boton crear = Tema.botonPrimario("Crear usuario");
        crear.addActionListener(e -> crear());
        form.add(Tema.fila(8, crear));
        add(form);

        refrescar();
    }

    /** Mensaje que parte las lineas si no cabe (nunca se corta). */
    private static Tema.Parrafo mensaje() {
        Tema.Parrafo l = Tema.parrafo(" ", 300);
        l.setFont(Tema.texto(13));
        return l;
    }

    private static void decir(Tema.Parrafo l, String texto, boolean bien) {
        l.setForeground(bien ? Tema.EXITO_TEXTO : Tema.ERROR_TEXTO);
        l.setText(texto);
    }

    private long miId() {
        return app.getSesion().getUsuario().getId();
    }

    private void refrescar() {
        Usuario sel = lista.getSelectedValue();
        modelo.clear();
        try {
            for (Usuario u : bd.listarUsuarios()) {
                modelo.addElement(u);
                if (sel != null && u.getId() == sel.getId()) {
                    lista.setSelectedIndex(modelo.size() - 1);
                }
            }
        } catch (SQLException e) {
            decir(lblLista, "No se pudo leer: " + e.getMessage(), false);
        }
        actualizarBotones();
    }

    private void actualizarBotones() {
        Usuario u = lista.getSelectedValue();
        btnClave.setEnabled(u != null);
        btnActivo.setEnabled(u != null && u.getId() != miId());
        btnActivo.setText(u != null && !u.isActivo() ? "Activar" : "Desactivar");
        btnActivo.setToolTipText(u != null && u.getId() == miId() ? "Es su propio usuario" : null);
    }

    private void crear() {
        char[] c1 = campoClave.getPassword();
        char[] c2 = campoClave2.getPassword();
        try {
            if (!Arrays.equals(c1, c2)) {
                decir(lblForm, "Las contraseñas no coinciden", false);
                return;
            }
            Usuario u = bd.crearUsuario(campoUsuario.getText(), campoNombre.getText(),
                    (Rol) comboRol.getSelectedItem(), c1);
            decir(lblForm, "Listo: " + u.getNombre() + " entra con el usuario «" + u.getUsuario() + "»", true);
            campoNombre.setText("");
            campoUsuario.setText("");
            campoClave.setText("");
            campoClave2.setText("");
            refrescar();
        } catch (IllegalArgumentException e) {
            decir(lblForm, e.getMessage(), false);
        } catch (SQLException e) {
            decir(lblForm, "No se pudo guardar: " + e.getMessage(), false);
        } finally {
            Arrays.fill(c1, '\0');
            Arrays.fill(c2, '\0');
        }
    }

    private void cambiarClave() {
        Usuario u = lista.getSelectedValue();
        if (u == null) {
            return;
        }
        JPasswordField p1 = Tema.campoClave("Nueva contraseña (mínimo 6)");
        JPasswordField p2 = Tema.campoClave("Repita la contraseña");
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(p1);
        panel.add(Tema.espacio(6));
        panel.add(p2);
        int r = JOptionPane.showConfirmDialog(this, panel, "Contraseña de " + u.getNombre(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] c1 = p1.getPassword();
        char[] c2 = p2.getPassword();
        try {
            if (r != JOptionPane.OK_OPTION) {
                return;
            }
            if (!Arrays.equals(c1, c2)) {
                decir(lblLista, "Las contraseñas no coinciden", false);
                return;
            }
            bd.cambiarClave(u.getId(), c1);
            decir(lblLista, "Contraseña de " + u.getNombre() + " cambiada", true);
        } catch (IllegalArgumentException e) {
            decir(lblLista, e.getMessage(), false);
        } catch (SQLException e) {
            decir(lblLista, "No se pudo guardar: " + e.getMessage(), false);
        } finally {
            Arrays.fill(c1, '\0');
            Arrays.fill(c2, '\0');
        }
    }

    private void cambiarActivo() {
        Usuario u = lista.getSelectedValue();
        if (u == null || u.getId() == miId()) {
            return;
        }
        try {
            bd.cambiarActivo(u.getId(), !u.isActivo());
            decir(lblLista, u.getNombre() + (u.isActivo() ? " ya no puede entrar" : " puede entrar de nuevo"), true);
            refrescar();
        } catch (IllegalStateException e) {
            decir(lblLista, e.getMessage(), false);
        } catch (SQLException e) {
            decir(lblLista, "No se pudo guardar: " + e.getMessage(), false);
        }
    }

    private class Render extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean foco) {
            Usuario u = (Usuario) value;
            String detalle = "Usuario «" + u.getUsuario() + "» · " + u.getRol().getTexto()
                    + (u.isActivo() ? "" : " · Desactivado") + (u.getId() == miId() ? " · Usted" : "");
            return Tema.celda(list, u.getNombre(), Tema.textoFuerte(14),
                    u.isActivo() ? Tema.GRIS_OSCURO : Tema.GRIS_TEXTO, detalle, sel, Color.WHITE);
        }
    }
}
