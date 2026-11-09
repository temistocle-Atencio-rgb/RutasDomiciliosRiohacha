package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import rutas.logica.BaseGastos;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.modelo.Usuario;

/**
 * Gastos › Gastos por categoría: cuánto se fue en cada cosa entre dos fechas, qué parte del total es
 * y cómo va contra el mismo largo de tiempo justo antes. Arriba: ventas, gastos y lo que queda.
 */
public class PanelGastosPorCategoria extends ListaGastos<BaseGastos.Total> {

    private static final String[] COLUMNAS = {"Item", "Categoría", "Cantidad", "Total", "% del total",
        "Periodo anterior", "Cambio"};

    private final Controles.CampoFecha fechaInicial = new Controles.CampoFecha("Fecha inicial");
    private final Controles.CampoFecha fechaFinal = new Controles.CampoFecha("Fecha final");
    private final JComboBox<LocalTime> horaInicial = comboHoras(false);
    private final JComboBox<LocalTime> horaFinal = comboHoras(true);
    private final JComboBox<Object> comboCategoria = new JComboBox<>();
    private final JComboBox<Object> comboUsuario = new JComboBox<>();
    private final JComboBox<String> comboTipo = new JComboBox<>(new String[]{"TODOS", "CONTADO", "CRÉDITO"});
    private final JLabel lblVentas = numero();
    private final JLabel lblGastos = numero();
    private final JLabel lblQueda = numero();
    private long total;

    public PanelGastosPorCategoria(Aplicacion app) {
        super(app, "Gastos por Categoría", COLUMNAS, new int[]{50, 240, 100, 140, 260, 150, 120},
                "No hay registros", "No hay gastos entre esas fechas.", "categorias");
        armar();
        alMostrar();
        consultar();
    }

    @Override
    protected JComponent filtros() {
        fechaInicial.setFecha(LocalDate.now().withDayOfMonth(1));
        fechaFinal.setFecha(LocalDate.now());
        Tema.estiloCombo(comboCategoria, PanelGastos::nombre);
        Tema.estiloCombo(comboUsuario, PanelGastos::nombre);
        Tema.estiloCombo(comboTipo, String::valueOf);
        comboCategoria.getAccessibleContext().setAccessibleName("Categoría");
        comboUsuario.getAccessibleContext().setAccessibleName("Usuario");
        comboTipo.getAccessibleContext().setAccessibleName("Tipo de gasto");
        JPanel f = new JPanel(new GridBagLayout());
        f.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.NORTHWEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 0, 14);
        g.weightx = 0.2;
        f.add(fechaConHora("Fecha inicial", fechaInicial, horaInicial), g);
        f.add(fechaConHora("Fecha final", fechaFinal, horaFinal), g);
        f.add(apilados("Categoría", comboCategoria, "Usuario", comboUsuario), g);
        f.add(apilados("Tipo gasto", comboTipo, null, null), g);
        Tema.Boton consultar = Tema.botonPrimario("Consultar");
        consultar.setIcon(Iconos.de("lupa", 16, Tema.BLANCO));
        consultar.addActionListener(e -> consultar());
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        g.insets = new Insets(0, 0, 0, 0);
        f.add(conLeyenda(consultar, null), g);
        g.weightx = 0.2;
        f.add(Box.createHorizontalGlue(), g);
        JPanel cuadros = new JPanel(new GridLayout(1, 3, 12, 0));
        cuadros.setOpaque(false);
        cuadros.add(cuadro("Ventas", lblVentas, "Ventas cobradas en esas fechas"));
        cuadros.add(cuadro("Gastos", lblGastos, "Sin los anulados"));
        cuadros.add(cuadro("Lo que queda", lblQueda, "Ventas menos gastos (sin restar el costo de la mercancía)"));
        JPanel todo = new JPanel(new BorderLayout(0, 14));
        todo.setOpaque(false);
        todo.add(f, BorderLayout.NORTH);
        todo.add(cuadros, BorderLayout.CENTER);
        return todo;
    }

    @Override
    protected void alMostrar() {
        try {
            Object c = comboCategoria.getSelectedItem(), u = comboUsuario.getSelectedItem();
            comboCategoria.removeAllItems();
            comboCategoria.addItem("TODAS");
            for (BaseGastos.Categoria k : app.getGastos().categorias(false)) {
                comboCategoria.addItem(k);
                if (c instanceof BaseGastos.Categoria && ((BaseGastos.Categoria) c).id == k.id) {
                    comboCategoria.setSelectedItem(k);
                }
            }
            comboUsuario.removeAllItems();
            comboUsuario.addItem("TODOS");
            for (Usuario x : app.getTienda().listarUsuarios()) {
                comboUsuario.addItem(x);
                if (u instanceof Usuario && ((Usuario) u).getId() == x.getId()) {
                    comboUsuario.setSelectedItem(x);
                }
            }
        } catch (Exception e) {
            Ubicaciones.registrarError(e);
        }
    }

    @Override
    protected List<BaseGastos.Total> leer() throws Exception {
        LocalDateTime d = momento(fechaInicial, horaInicial), h = momento(fechaFinal, horaFinal);
        Object c = comboCategoria.getSelectedItem(), u = comboUsuario.getSelectedItem();
        String t = (String) comboTipo.getSelectedItem();
        List<BaseGastos.Total> l = app.getGastos().porCategoria(d, h,
                c instanceof BaseGastos.Categoria ? ((BaseGastos.Categoria) c).id : 0,
                u instanceof Usuario ? ((Usuario) u).getId() : 0,
                "CONTADO".equals(t) ? Boolean.FALSE : "CRÉDITO".equals(t) ? Boolean.TRUE : null);
        total = 0;
        for (BaseGastos.Total x : l) {
            total += x.total;
        }
        long ventas = app.getGastos().ventas(d, h);
        lblVentas.setText(Tarifa.pesos(ventas));
        lblGastos.setText(Tarifa.pesos(total));
        lblQueda.setText(Tarifa.pesos(ventas - total));
        lblQueda.setForeground(ventas - total < 0 ? Tema.ERROR_TEXTO : Tema.EXITO_TEXTO);
        return l;
    }

    @Override
    protected Object valor(BaseGastos.Total x, int c) {
        switch (c) {
            case 1:
                return x.categoria;
            case 2:
                return x.cantidad;
            case 3:
                return Tarifa.pesos(x.total);
            case 4:
                return total == 0 ? "0 %" : String.format(new java.util.Locale("es", "CO"), "%.1f %%", 100.0 * x.total / total);
            case 5:
                return Tarifa.pesos(x.anterior);
            case 6:
                return cambio(x);
            default:
                return "";
        }
    }

    static String cambio(BaseGastos.Total x) {
        if (x.anterior == 0) {
            return x.total == 0 ? "—" : "Nuevo";
        }
        long p = Math.round(100.0 * (x.total - x.anterior) / x.anterior);
        return (p > 0 ? "▲ " + p : p < 0 ? "▼ " + (-p) : "= 0") + " %";
    }

    @Override
    protected double barra(BaseGastos.Total x, int c) {
        return c == 4 && total > 0 ? (double) x.total / total : -1;
    }

    @Override
    protected Color color(BaseGastos.Total x, int c) {
        if (c == 6) {
            // gastar mas que antes es lo que hay que mirar
            return x.anterior > 0 && x.total > x.anterior ? Tema.ERROR_TEXTO
                    : x.anterior > 0 && x.total < x.anterior ? Tema.EXITO_TEXTO : Tema.GRIS_TEXTO;
        }
        return c == 5 ? Tema.GRIS_TEXTO : null;
    }

    @Override
    protected boolean negrita(BaseGastos.Total x, int c) {
        return c == 3 || c == 6;
    }

    @Override
    protected int alineacion(int c) {
        return c == 1 ? SwingConstants.LEFT : c == 3 || c == 5 ? SwingConstants.RIGHT : SwingConstants.CENTER;
    }

    @Override
    protected String resumen(List<BaseGastos.Total> l) {
        return "Total gastos: " + Tarifa.pesos(total);
    }
}
