package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import rutas.logica.BaseCartera.Presupuesto;
import rutas.logica.BaseGastos;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;

/**
 * Gastos › Presupuesto: cuánto se piensa gastar cada mes en cada categoría y cuánto se lleva.
 * Primero la categoría más pasada (montículo). El valor puede repetirse todos los meses.
 */
public class PanelPresupuestos extends ListaGastos<Presupuesto> {

    private static final String[] COLUMNAS = {"Item", "Categoría", "Presupuesto", "Gastado", "% usado", "Queda", "Acción"};
    static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MMMM yyyy", new Locale("es", "CO"));

    private final JComboBox<YearMonth> comboMes = new JComboBox<>();
    private final JLabel lblPresupuesto = numero();
    private final JLabel lblGastado = numero();
    private final JLabel lblQueda = numero();

    public PanelPresupuestos(Aplicacion app) {
        super(app, "Presupuesto de gastos", COLUMNAS, new int[]{44, 200, 180, 130, 250, 130, 80},
                "No hay presupuesto para este mes", "Oprima «Asignar presupuesto» o «Copiar del mes anterior».", "categorias");
        armar();
        consultar();
    }

    static String textoMes(YearMonth m) {
        String s = m.format(MES);
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    @Override
    protected JComponent filtros() {
        YearMonth hoy = YearMonth.now();
        for (int i = 12; i >= -2; i--) {
            comboMes.addItem(hoy.minusMonths(i));
        }
        comboMes.setSelectedItem(hoy);
        comboMes.setMaximumRowCount(15);
        Tema.estiloCombo(comboMes, PanelPresupuestos::textoMes);
        comboMes.getAccessibleContext().setAccessibleName("Mes");
        comboMes.addActionListener(e -> consultar());
        JPanel f = new JPanel(new GridBagLayout());
        f.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.SOUTHWEST;
        g.insets = new Insets(0, 0, 0, 12);
        JComponent col = apilados("Mes", comboMes, null, null);
        col.setPreferredSize(new java.awt.Dimension(210, col.getPreferredSize().height));
        f.add(col, g);
        g.weightx = 1;
        f.add(Box.createHorizontalGlue(), g);
        g.weightx = 0;
        Tema.Boton copiar = Tema.botonSecundario("Copiar del mes anterior");
        copiar.setToolTipText("Pone en este mes los valores del mes pasado");
        copiar.addActionListener(e -> copiar());
        f.add(copiar, g);
        g.insets = new Insets(0, 0, 0, 0);
        Tema.Boton nuevo = Tema.botonAccion("Asignar presupuesto");
        nuevo.addActionListener(e -> editar(null));
        f.add(nuevo, g);
        JPanel cuadros = new JPanel(new GridLayout(1, 3, 12, 0));
        cuadros.setOpaque(false);
        cuadros.add(cuadro("Presupuestado", lblPresupuesto, "Lo que se piensa gastar este mes"));
        cuadros.add(cuadro("Gastado", lblGastado, "Gastos del mes sin los anulados"));
        cuadros.add(cuadro("Queda", lblQueda, "Presupuesto menos lo gastado"));
        JPanel todo = new JPanel(new BorderLayout(0, 14));
        todo.setOpaque(false);
        todo.add(f, BorderLayout.NORTH);
        todo.add(cuadros, BorderLayout.CENTER);
        return todo;
    }

    private YearMonth mes() {
        return (YearMonth) comboMes.getSelectedItem();
    }

    @Override
    protected List<Presupuesto> leer() throws Exception {
        List<Presupuesto> l = app.getCartera().presupuesto(mes());
        long p = 0, g = 0;
        for (Presupuesto x : l) {
            p += x.valor;
            g += x.gastado;
        }
        lblPresupuesto.setText(Tarifa.pesos(p));
        lblGastado.setText(Tarifa.pesos(g));
        lblQueda.setText(Tarifa.pesos(p - g));
        lblQueda.setForeground(p - g < 0 ? Tema.ERROR_TEXTO : Tema.EXITO_TEXTO);
        return l;
    }

    @Override
    protected Object valor(Presupuesto x, int c) {
        switch (c) {
            case 1:
                return x.categoria;
            case 2:
                return x.valor > 0 ? Tarifa.pesos(x.valor) + (x.todosLosMeses ? "  (cada mes)" : "") : "Sin presupuesto";
            case 3:
                return Tarifa.pesos(x.gastado);
            case 4:
                return x.valor > 0 ? Math.round(x.usado()) + " %" : "—";
            case 5:
                return x.valor > 0 ? Tarifa.pesos(x.queda()) : "—";
            default:
                return "";
        }
    }

    @Override
    protected double barra(Presupuesto x, int c) {
        return c == 4 && x.valor > 0 ? Math.min(1, x.usado() / 100) : -1;
    }

    @Override
    protected List<Accion> acciones(Presupuesto x, int c) {
        return c == 6 ? List.of(new Accion(PanelProductos.Icono.LAPIZ, null, Tema.AZUL, "Cambiar el presupuesto", () -> editar(x)))
                : List.of();
    }

    @Override
    protected Color color(Presupuesto x, int c) {
        if (c == 4 || c == 5) {
            return x.valor <= 0 ? Tema.GRIS_TEXTO : x.usado() > 100 ? Tema.ERROR_TEXTO : x.usado() >= 80 ? Tema.AVISO_TEXTO
                    : Tema.EXITO_TEXTO;
        }
        return c == 2 && x.valor <= 0 ? Tema.GRIS_TEXTO : null;
    }

    @Override
    protected boolean negrita(Presupuesto x, int c) {
        return c == 4 || c == 5;
    }

    @Override
    protected int alineacion(int c) {
        return c == 1 ? SwingConstants.LEFT : c == 2 || c == 3 || c == 5 ? SwingConstants.RIGHT : SwingConstants.CENTER;
    }

    @Override
    protected String resumen(List<Presupuesto> l) {
        int pasadas = 0;
        for (Presupuesto x : l) {
            if (x.valor > 0 && x.usado() > 100) {
                pasadas++;
            }
        }
        return textoMes(mes()) + (pasadas > 0 ? "    ·    " + pasadas + (pasadas == 1 ? " categoría pasada" : " categorías pasadas")
                + " del presupuesto" : "");
    }

    @Override
    protected void abrir(Presupuesto x) {
        editar(x);
    }

    @Override
    protected String subtituloPdf() {
        return textoMes(mes());
    }

    private void copiar() {
        try {
            int n = app.getCartera().copiarMesAnterior(mes());
            recargar();
            Aviso.mostrar(this, n > 0 ? Aviso.Tipo.BIEN : Aviso.Tipo.AVISO, n > 0 ? "Presupuesto copiado" : "No había nada que copiar",
                    n > 0 ? n + (n == 1 ? " categoría" : " categorías") + " de " + textoMes(mes().minusMonths(1))
                            : "El mes anterior no tenía valores de ese mes solo");
        } catch (java.sql.SQLException e) {
            Ubicaciones.registrarError(e);
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo copiar", e.getMessage(), null);
        }
    }

    private void editar(Presupuesto x) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        JPanel t = Capa.tarjeta(460, 24, 30);
        t.add(Tema.tituloPantalla("Asignar presupuesto · " + textoMes(mes())));
        t.add(Tema.espacio(12));
        JComboBox<Object> cat = new JComboBox<>();
        Tema.estiloCombo(cat, PanelGastos::nombre);
        cat.getAccessibleContext().setAccessibleName("Categoría");
        try {
            PanelGastos.llenarCategorias(app, cat, x == null ? 0 : x.categoriaId);
        } catch (java.sql.SQLException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudieron leer las categorías", e.getMessage(), null);
            return;
        }
        cat.setEnabled(x == null);
        PanelGastos.agregar(t, "Categoría", cat);
        JTextField valor = Controles.campoNumero("$ 0");
        valor.getAccessibleContext().setAccessibleName("Valor del presupuesto");
        if (x != null && x.valor > 0) {
            valor.setText(String.valueOf(x.valor));
        }
        PanelGastos.agregar(t, "Valor del mes", valor);
        JCheckBox todos = Tema.casilla("Repetir todos los meses (desde " + textoMes(mes()) + ")");
        todos.setSelected(x == null || x.todosLosMeses);
        todos.setAlignmentX(LEFT_ALIGNMENT);
        t.add(todos);
        JLabel error = PanelGastos.errorLabel();
        t.add(Tema.espacio(4));
        t.add(error);
        t.add(Tema.espacio(8));
        Tema.Boton si = Tema.botonPrimario("Guardar presupuesto");
        Tema.Boton no = Tema.botonSecundario("Cancelar");
        JPanel f = Tema.fila(8, no, si);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        Capa capa = new Capa(this, t, true);
        Runnable hacer = () -> {
            try {
                if (!(cat.getSelectedItem() instanceof BaseGastos.Categoria)) {
                    throw new IllegalArgumentException("Escoja la categoría");
                }
                if (valor.getText().replaceAll("\\D", "").isEmpty()) {
                    throw new IllegalArgumentException("Escriba el valor (0 para quitar el presupuesto)");
                }
                app.getCartera().asignar(((BaseGastos.Categoria) cat.getSelectedItem()).id, mes(),
                        PanelGastos.pesos(valor.getText()), todos.isSelected());
                capa.cerrar();
                recargar();
                Aviso.mostrar(this, Aviso.Tipo.BIEN, "Presupuesto guardado", todos.isSelected() ? "Se repite cada mes"
                        : "Solo para " + textoMes(mes()));
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
            } catch (java.sql.SQLException ex) {
                Ubicaciones.registrarError(ex);
                error.setText("No se pudo guardar: " + ex.getMessage());
            }
        };
        si.addActionListener(e -> hacer.run());
        valor.addActionListener(e -> hacer.run());
        no.addActionListener(e -> capa.cerrar());
        capa.mostrar(x == null ? cat : valor);
    }
}
