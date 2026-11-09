package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import rutas.logica.BaseGastos;
import rutas.logica.MediosPago;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.modelo.MetodoPago;
import rutas.modelo.Usuario;

/** Gastos › Historial de abonos: lo que se le ha ido pagando a los gastos a crédito. */
public class PanelAbonosGastos extends ListaGastos<BaseGastos.Abono> {

    private static final String[] COLUMNAS = {"Item", "Gasto", "Fecha de abono", "Descripción gasto", "Proveedor",
        "Responsable", "Método de pago", "Valor", "Salió caja"};

    private final Controles.CampoFecha fechaInicial = new Controles.CampoFecha("Fecha inicial");
    private final Controles.CampoFecha fechaFinal = new Controles.CampoFecha("Fecha final");
    private final JComboBox<LocalTime> horaInicial = comboHoras(false);
    private final JComboBox<LocalTime> horaFinal = comboHoras(true);
    private final JComboBox<Object> comboUsuario = new JComboBox<>();
    private final JComboBox<Object> comboMetodo = new JComboBox<>();
    private final JTextField campoBuscar = Tema.campo("Buscar por gasto o proveedor");

    public PanelAbonosGastos(Aplicacion app) {
        super(app, "Historial de Abonos Gastos", COLUMNAS, new int[]{50, 80, 160, 230, 160, 130, 130, 110, 100},
                "Sin registros de abonos para el filtro aplicado", "Los abonos se hacen desde el detalle de un gasto a crédito.",
                "notas");
        armar();
        alMostrar();
        consultar();
    }

    @Override
    protected JComponent filtros() {
        fechaInicial.setFecha(LocalDate.now().withDayOfMonth(1));
        fechaFinal.setFecha(LocalDate.now());
        Tema.estiloCombo(comboUsuario, PanelGastos::nombre);
        Tema.estiloCombo(comboMetodo, PanelGastos::nombre);
        comboUsuario.getAccessibleContext().setAccessibleName("Usuario");
        comboMetodo.getAccessibleContext().setAccessibleName("Método de pago");
        JPanel f = new JPanel(new GridBagLayout());
        f.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.NORTHWEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 0, 14);
        g.weightx = 0.2;
        f.add(fechaConHora("Fecha inicial", fechaInicial, horaInicial), g);
        f.add(fechaConHora("Fecha final", fechaFinal, horaFinal), g);
        f.add(apilados("Usuario", comboUsuario, "Método de pago", comboMetodo), g);
        Tema.Boton consultar = Tema.botonPrimario("Consultar");
        consultar.setIcon(Iconos.de("lupa", 16, Tema.BLANCO));
        consultar.addActionListener(e -> consultar());
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        f.add(conLeyenda(consultar, null), g);
        g.weightx = 0.3;
        f.add(Box.createHorizontalGlue(), g);
        Tema.Boton regresar = Tema.botonSecundario("Regresar");
        regresar.setToolTipText("Volver al historial de gastos");
        regresar.addActionListener(e -> VentanaPrincipal.irA(this, "Gastos"));
        g.weightx = 0;
        g.insets = new Insets(0, 0, 0, 0);
        f.add(conLeyenda(regresar, null), g);
        JPanel todo = new JPanel(new BorderLayout(0, 10));
        todo.setOpaque(false);
        todo.add(f, BorderLayout.CENTER);
        campoBuscar.setPreferredSize(new java.awt.Dimension(320, 38));
        campoBuscar.getAccessibleContext().setAccessibleName("Buscar por gasto o proveedor");
        campoBuscar.getDocument().addDocumentListener(PanelProductos.alCambiar(this::consultar));
        JPanel b = new JPanel(new BorderLayout());
        b.setOpaque(false);
        b.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        b.add(campoBuscar, BorderLayout.EAST);
        todo.add(b, BorderLayout.SOUTH);
        return todo;
    }

    @Override
    protected void alMostrar() {
        try {
            Object u = comboUsuario.getSelectedItem(), m = comboMetodo.getSelectedItem();
            comboUsuario.removeAllItems();
            comboUsuario.addItem("TODOS");
            for (Usuario x : app.getTienda().listarUsuarios()) {
                comboUsuario.addItem(x);
                if (u instanceof Usuario && ((Usuario) u).getId() == x.getId()) {
                    comboUsuario.setSelectedItem(x);
                }
            }
            comboMetodo.removeAllItems();
            comboMetodo.addItem("TODOS");
            for (MetodoPago x : MediosPago.activos(app.getTienda())) {
                comboMetodo.addItem(x);
            }
            if (m instanceof MetodoPago) {
                comboMetodo.setSelectedItem(m);
            }
        } catch (Exception e) {
            Ubicaciones.registrarError(e);
        }
    }

    @Override
    protected List<BaseGastos.Abono> leer() throws Exception {
        Object u = comboUsuario.getSelectedItem(), m = comboMetodo.getSelectedItem();
        return app.getGastos().abonos(momento(fechaInicial, horaInicial), momento(fechaFinal, horaFinal),
                u instanceof Usuario ? ((Usuario) u).getId() : 0, m instanceof MetodoPago ? (MetodoPago) m : null,
                campoBuscar.getText());
    }

    @Override
    protected Object valor(BaseGastos.Abono a, int c) {
        switch (c) {
            case 1:
                return "No. " + a.gastoNumero;
            case 2:
                return PanelHistorialCompras.textoFecha(a.fecha);
            case 3:
                return a.descripcion.isEmpty() ? "—" : a.descripcion;
            case 4:
                return a.proveedor.isEmpty() ? "Sin proveedor" : a.proveedor;
            case 5:
                return a.responsable;
            case 6:
                return a.metodo.toString();
            case 7:
                return Tarifa.pesos(a.valor);
            case 8:
                return a.saleCaja ? "Sí" : "No";
            default:
                return "";
        }
    }

    @Override
    protected Color color(BaseGastos.Abono a, int c) {
        return (c == 4 && a.proveedor.isEmpty()) || (c == 8 && !a.saleCaja) ? Tema.GRIS_TEXTO : null;
    }

    @Override
    protected boolean negrita(BaseGastos.Abono a, int c) {
        return c == 7;
    }

    @Override
    protected int alineacion(int c) {
        return c == 3 || c == 4 ? SwingConstants.LEFT : c == 7 ? SwingConstants.RIGHT : SwingConstants.CENTER;
    }

    @Override
    protected String resumen(List<BaseGastos.Abono> l) {
        long total = 0, caja = 0;
        for (BaseGastos.Abono a : l) {
            total += a.valor;
            caja += a.saleCaja ? a.valor : 0;
        }
        return l.size() + (l.size() == 1 ? " abono" : " abonos") + "    ·    Total abonado: " + Tarifa.pesos(total)
                + "    ·    Salió de la caja: " + Tarifa.pesos(caja);
    }

    List<BaseGastos.Abono> todas() {
        return new ArrayList<>(filas);
    }
}
