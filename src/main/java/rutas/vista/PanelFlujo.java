package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import rutas.logica.BaseCartera;
import rutas.logica.Tarifa;

/**
 * Cuentas › Flujo de efectivo: lo que entró y salió de verdad entre dos fechas, agrupado por
 * actividad (operación, inversión) con su total, o por medio de pago (la fila de efectivo es lo que
 * debe haber de más o de menos en el cajón).
 */
public class PanelFlujo extends ListaGastos<PanelFlujo.Fila> {

    /** Una fila de la tabla: un movimiento o el total de un grupo. */
    static final class Fila {
        final String grupo;
        final String concepto;
        final long entradas;
        final long salidas;
        final boolean total;

        Fila(String grupo, String concepto, long entradas, long salidas, boolean total) {
            this.grupo = grupo;
            this.concepto = concepto;
            this.entradas = entradas;
            this.salidas = salidas;
            this.total = total;
        }

        long neto() {
            return entradas - salidas;
        }
    }

    private static final String[] COLUMNAS = {"Item", "Actividad", "Concepto", "Entradas", "Salidas", "Neto"};
    private static final String POR_ACTIVIDAD = "Por actividad", POR_MEDIO = "Por medio de pago";

    private final Controles.CampoFecha fechaInicial = new Controles.CampoFecha("Fecha inicial");
    private final Controles.CampoFecha fechaFinal = new Controles.CampoFecha("Fecha final");
    private final JComboBox<LocalTime> horaInicial = comboHoras(false);
    private final JComboBox<LocalTime> horaFinal = comboHoras(true);
    private final JComboBox<String> comboVer = new JComboBox<>(new String[]{POR_ACTIVIDAD, POR_MEDIO});
    private final JLabel lblEntro = numero();
    private final JLabel lblSalio = numero();
    private final JLabel lblQuedo = numero();

    public PanelFlujo(Aplicacion app) {
        super(app, "Flujo de efectivo", COLUMNAS, new int[]{44, 160, 320, 150, 150, 150},
                "No hubo movimientos de plata", "Cambie las fechas y oprima «Consultar».", "notas");
        armar();
        consultar();
    }

    @Override
    protected JComponent filtros() {
        fechaInicial.setFecha(LocalDate.now().withDayOfMonth(1));
        fechaFinal.setFecha(LocalDate.now());
        Tema.estiloCombo(comboVer, String::valueOf);
        comboVer.getAccessibleContext().setAccessibleName("Ver");
        comboVer.addActionListener(e -> consultar());
        JPanel f = new JPanel(new GridBagLayout());
        f.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.NORTHWEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 0, 14);
        g.weightx = 0.2;
        f.add(fechaConHora("Fecha inicial", fechaInicial, horaInicial), g);
        f.add(fechaConHora("Fecha final", fechaFinal, horaFinal), g);
        f.add(apilados("Ver", comboVer, null, null), g);
        Tema.Boton consultar = Tema.botonPrimario("Consultar");
        consultar.setIcon(Iconos.de("lupa", 16, Tema.BLANCO));
        consultar.addActionListener(e -> consultar());
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        f.add(conLeyenda(consultar, null), g);
        g.weightx = 0.3;
        f.add(Box.createHorizontalGlue(), g);
        JPanel cuadros = new JPanel(new GridLayout(1, 3, 12, 0));
        cuadros.setOpaque(false);
        cuadros.add(cuadro("Entró", lblEntro, "Ventas cobradas y abonos de clientes"));
        cuadros.add(cuadro("Salió", lblSalio, "Compras, gastos y abonos a proveedores"));
        cuadros.add(cuadro("Quedó", lblQuedo, "Entró menos salió"));
        JPanel todo = new JPanel(new BorderLayout(0, 14));
        todo.setOpaque(false);
        todo.add(f, BorderLayout.NORTH);
        todo.add(cuadros, BorderLayout.CENTER);
        return todo;
    }

    private boolean porMedio() {
        return POR_MEDIO.equals(comboVer.getSelectedItem());
    }

    @Override
    protected List<Fila> leer() throws Exception {
        List<BaseCartera.Movimiento> l = app.getCartera().flujo(momento(fechaInicial, horaInicial), momento(fechaFinal, horaFinal));
        long e = 0, s = 0;
        for (BaseCartera.Movimiento m : l) {
            e += m.entradas;
            s += m.salidas;
        }
        lblEntro.setText(Tarifa.pesos(e));
        lblSalio.setText(Tarifa.pesos(s));
        lblQuedo.setText(Tarifa.pesos(e - s));
        lblQuedo.setForeground(e - s < 0 ? Tema.ERROR_TEXTO : Tema.EXITO_TEXTO);
        List<Fila> r = new ArrayList<>();
        if (porMedio()) {
            for (BaseCartera.Movimiento m : BaseCartera.porMedio(l)) {
                r.add(new Fila("Medio de pago", m.concepto, m.entradas, m.salidas, false));
            }
        } else {
            for (BaseCartera.Actividad a : BaseCartera.Actividad.values()) {
                long ea = 0, sa = 0;
                boolean hay = false;
                for (BaseCartera.Movimiento m : l) {
                    if (m.actividad == a) {
                        r.add(new Fila(a.texto, m.concepto, m.entradas, m.salidas, false));
                        ea += m.entradas;
                        sa += m.salidas;
                        hay = true;
                    }
                }
                if (hay) {
                    r.add(new Fila(a.texto, "Total " + a.texto.toLowerCase(), ea, sa, true));
                }
            }
        }
        if (!r.isEmpty()) {
            r.add(new Fila("", "TOTAL DEL PERIODO", e, s, true));
        }
        return r;
    }

    @Override
    protected Object valor(Fila x, int c) {
        switch (c) {
            case 1:
                return x.total ? "" : x.grupo;
            case 2:
                return x.concepto;
            case 3:
                return x.entradas == 0 ? "—" : Tarifa.pesos(x.entradas);
            case 4:
                return x.salidas == 0 ? "—" : Tarifa.pesos(x.salidas);
            case 5:
                return Tarifa.pesos(x.neto());
            default:
                return "";
        }
    }

    @Override
    protected Color color(Fila x, int c) {
        if (c == 5) {
            return x.neto() < 0 ? Tema.ERROR_TEXTO : x.neto() > 0 ? Tema.EXITO_TEXTO : Tema.GRIS_TEXTO;
        }
        if (x.total) {
            return Tema.AZUL_PROFUNDO;
        }
        return "—".equals(valor(x, c)) ? Tema.GRIS_TEXTO : null;
    }

    @Override
    protected boolean negrita(Fila x, int c) {
        return x.total || c == 5;
    }

    @Override
    protected int alineacion(int c) {
        return c == 2 ? SwingConstants.LEFT : c >= 3 ? SwingConstants.RIGHT : SwingConstants.CENTER;
    }

    @Override
    protected String resumen(List<Fila> l) {
        return porMedio() ? "En la fila de Efectivo está lo que debió entrar o salir del cajón"
                : "Lo fiado cuenta cuando lo abonan; lo de crédito, cuando se abona al proveedor";
    }

    @Override
    protected String subtituloPdf() {
        LocalDate a = fechaInicial.fechaSiEsValida(), b = fechaFinal.fechaSiEsValida();
        return a == null || b == null ? "" : a.format(PanelCartera.DIA) + " al " + b.format(PanelCartera.DIA);
    }
}
