package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import rutas.logica.Tarifa;
import rutas.modelo.Domiciliario;
import rutas.modelo.Ruta;
import rutas.modelo.Salida;

/**
 * Historial de salidas: las salidas ya cerradas de un periodo, cuánto entregó y cobró cada
 * domiciliario, y el detalle de cada salida con las fotos de entrega.
 */
public class PanelHistorialSalidas extends Tema.Tarjeta {

    /** Los periodos que se pueden ver. */
    public enum Periodo {
        HOY("Hoy"), AYER("Ayer"), SEMANA("Últimos 7 días"), MES("Este mes"), MES_PASADO("Mes pasado");

        private final String texto;

        Periodo(String texto) {
            this.texto = texto;
        }

        /** {desde, hasta}, los dos días incluidos. */
        public LocalDate[] rango(LocalDate hoy) {
            switch (this) {
                case AYER:
                    return new LocalDate[]{hoy.minusDays(1), hoy.minusDays(1)};
                case SEMANA:
                    return new LocalDate[]{hoy.minusDays(6), hoy};
                case MES:
                    return new LocalDate[]{hoy.withDayOfMonth(1), hoy};
                case MES_PASADO:
                    LocalDate ini = hoy.withDayOfMonth(1).minusMonths(1);
                    return new LocalDate[]{ini, ini.plusMonths(1).minusDays(1)};
                default:
                    return new LocalDate[]{hoy, hoy};
            }
        }

        @Override
        public String toString() {
            return texto;
        }
    }

    /** Lo que suma un domiciliario en el periodo. */
    public static class Cuenta {
        public String nombre;
        public String tipo = "";
        public int salidas;
        public int entregadas;
        public int noEntregadas;
        public int cobro;       // lo que cobró en las puertas
        public int domicilios;  // lo que ganó: sus domicilios (independiente) o su tarifa por entrega (fijo)

        public boolean seQueda() {
            return tipo.equals("INDEPENDIENTE") || tipo.equals("GRUPO");
        }
    }

    private static final String TODOS = "Todos";
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("EEE d MMM", new Locale("es", "CO"));

    private final Aplicacion app;
    private final JComboBox<Periodo> periodo = new JComboBox<>(Periodo.values());
    private final JComboBox<String> quien = new JComboBox<>();
    private final JPanel resumen = columna();
    private final JPanel cuentas = columna();
    private final DefaultListModel<Salida> modelo = new DefaultListModel<>();
    private final Tema.Lista<Salida> lista = new Tema.Lista<>(modelo, "No hay salidas cerradas en este periodo.");
    private final JPanel detalle = columna();
    private List<Salida> cargadas = new ArrayList<>();
    private int firma = -1;      // salidas cerradas en memoria: si cambia, se vuelve a leer
    private boolean llenandoQuien;
    private String error = "";

    public PanelHistorialSalidas(Aplicacion app) {
        this.app = app;
        add(Tema.seccion("Salidas"));
        add(Tema.parrafo("Las salidas ya cerradas: lo que entregó y cobró cada domiciliario. "
                + "Toque una salida para ver sus entregas y las fotos.", 262));
        add(Tema.espacio(8));
        Tema.estiloCombo(periodo, String::valueOf);
        Tema.estiloCombo(quien, String::valueOf);
        Tema.nombreAccesible(periodo, "Periodo del historial");
        Tema.nombreAccesible(quien, "Domiciliario");
        JPanel filtros = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
        filtros.setOpaque(false);
        filtros.setAlignmentX(LEFT_ALIGNMENT);
        filtros.add(periodo);
        filtros.add(quien);
        filtros.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        add(filtros);
        add(Tema.espacio(10));
        add(resumen);
        add(cuentas);
        add(Tema.espacio(8));

        lista.setCellRenderer(new Render());
        lista.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                mostrarDetalle(lista.getSelectedValue());
            }
        });
        JScrollPane sc = new JScrollPane(lista);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(Tema.bordeLista());
        sc.setAlignmentX(LEFT_ALIGNMENT);
        sc.setPreferredSize(new Dimension(300, 220));
        sc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
        add(sc);
        add(Tema.espacio(8));
        add(detalle);

        periodo.addActionListener(e -> recargar());
        quien.addActionListener(e -> {
            if (!llenandoQuien) {
                mostrar();
            }
        });
        app.alCambiar(() -> {
            if (cerradasEnMemoria() != firma) { // se cerró una salida: que salga en el historial
                recargar();
            }
        });
        recargar();
    }

    private static JPanel columna() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private int cerradasEnMemoria() {
        int n = 0;
        for (Salida s : app.getSalidas()) {
            if (!s.abierta()) {
                n++;
            }
        }
        return n;
    }

    /** Lee de la base las salidas del periodo escogido. */
    void recargar() {
        firma = cerradasEnMemoria();
        LocalDate[] r = ((Periodo) periodo.getSelectedItem()).rango(LocalDate.now());
        try {
            cargadas = app.historialSalidas(r[0], r[1]);
            error = "";
        } catch (Exception ex) {
            cargadas = new ArrayList<>();
            error = "No se pudo leer el historial: " + ex.getMessage();
        }
        // los domiciliarios que salieron en el periodo (se conserva el escogido si sigue)
        Object antes = quien.getSelectedItem();
        llenandoQuien = true;
        quien.removeAllItems();
        quien.addItem(TODOS);
        for (String n : contar(cargadas).keySet()) {
            quien.addItem(n);
        }
        quien.setSelectedItem(antes != null && contar(cargadas).containsKey(antes) ? antes : TODOS);
        llenandoQuien = false;
        mostrar();
    }

    /** Suma por domiciliario, en el orden en que salieron. */
    public static Map<String, Cuenta> contar(List<Salida> salidas) {
        Map<String, Cuenta> m = new LinkedHashMap<>();
        for (Salida s : salidas) {
            String n = s.repartidor.isEmpty() ? "Sin nombre" : s.repartidor;
            Cuenta c = m.computeIfAbsent(n, k -> {
                Cuenta x = new Cuenta();
                x.nombre = k;
                return x;
            });
            c.tipo = s.tipoRepartidor == null ? "" : s.tipoRepartidor;
            c.salidas++;
            c.entregadas += s.cuantas(Salida.Entrega.ENTREGADO);
            c.noEntregadas += s.cuantas(Salida.Entrega.NO_ENTREGADO);
            c.cobro += s.plataATraer();
            c.domicilios += s.ganancia();
        }
        return m;
    }

    private List<Salida> filtradas() {
        Object q = quien.getSelectedItem();
        if (q == null || TODOS.equals(q)) {
            return cargadas;
        }
        List<Salida> l = new ArrayList<>();
        for (Salida s : cargadas) {
            if ((s.repartidor.isEmpty() ? "Sin nombre" : s.repartidor).equals(q)) {
                l.add(s);
            }
        }
        return l;
    }

    private void mostrar() {
        List<Salida> ver = filtradas();
        Map<String, Cuenta> porQuien = contar(ver);
        // totales
        resumen.removeAll();
        if (!error.isEmpty()) {
            JLabel l = Tema.textoNormal(error);
            l.setForeground(Tema.ERROR_TEXTO);
            resumen.add(Tema.anchoLibre(l));
        }
        int entregadas = 0, no = 0, cobro = 0, deEllos = 0;
        for (Cuenta c : porQuien.values()) {
            entregadas += c.entregadas;
            no += c.noEntregadas;
            cobro += c.cobro;
            deEllos += c.domicilios;
        }
        JLabel l1 = Tema.textoNormal(ver.size() + (ver.size() == 1 ? " salida" : " salidas") + " · "
                + entregadas + (entregadas == 1 ? " entregada" : " entregadas") + " · "
                + no + (no == 1 ? " no entregada" : " no entregadas"));
        l1.setFont(Tema.textoFuerte(14));
        l1.setForeground(Tema.AZUL_PROFUNDO);
        resumen.add(Tema.anchoLibre(l1));
        JLabel l2 = Tema.textoNormal("Cobrado en las puertas: " + Tarifa.pesos(cobro));
        l2.setFont(Tema.texto(13.5f));
        resumen.add(Tema.anchoLibre(l2));
        if (deEllos > 0) {
            Tema.Parrafo l3 = Tema.parrafo("Ganancia de los domiciliarios: " + Tarifa.pesos(deEllos) + " (se quedan con eso)", 262);
            l3.setFont(Tema.texto(13));
            l3.setForeground(Tema.GRIS_TEXTO);
            resumen.add(l3);
        }
        // por domiciliario (solo si hay más de uno para comparar)
        cuentas.removeAll();
        if (porQuien.size() > 1) {
            cuentas.add(Tema.espacio(10));
            JLabel t = Tema.textoNormal("Por domiciliario");
            t.setFont(Tema.textoFuerte(13));
            t.setForeground(Tema.GRIS_TEXTO);
            cuentas.add(t);
            for (Cuenta c : porQuien.values()) {
                cuentas.add(Tema.espacio(6));
                cuentas.add(filaCuenta(c));
            }
        }
        modelo.clear();
        for (int i = ver.size() - 1; i >= 0; i--) { // la más nueva arriba
            modelo.addElement(ver.get(i));
        }
        mostrarDetalle(null);
        revalidate();
        repaint();
    }

    private static JComponent filaCuenta(Cuenta c) {
        JPanel p = columna();
        JLabel n = Tema.textoNormal(c.nombre + (tipoTexto(c.tipo).isEmpty() ? "" : " · " + tipoTexto(c.tipo)));
        n.setFont(Tema.textoFuerte(13.5f));
        p.add(Tema.anchoLibre(n));
        StringBuilder b = new StringBuilder();
        b.append(c.salidas).append(c.salidas == 1 ? " salida · " : " salidas · ")
                .append(c.entregadas).append(c.entregadas == 1 ? " entregada" : " entregadas");
        if (c.noEntregadas > 0) {
            b.append(" · ").append(c.noEntregadas).append(" no");
        }
        b.append(" · cobró ").append(Tarifa.pesos(c.cobro));
        if (c.domicilios > 0) {
            b.append(" · ganó ").append(Tarifa.pesos(c.domicilios));
        }
        Tema.Parrafo d = Tema.parrafo(b.toString(), 262);
        d.setFont(Tema.texto(13));
        d.setForeground(Tema.GRIS_TEXTO);
        p.add(d);
        return p;
    }

    static String tipoTexto(String tipo) {
        try {
            return tipo == null || tipo.isEmpty() ? "" : Domiciliario.Tipo.valueOf(tipo).toString();
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    // ------------------------------------------------------------------

    private void mostrarDetalle(Salida s) {
        detalle.removeAll();
        if (s != null) {
            JLabel t = Tema.textoNormal("Salida " + titulo(s));
            t.setFont(Tema.textoFuerte(14));
            t.setForeground(Tema.AZUL_PROFUNDO);
            detalle.add(Tema.anchoLibre(t));
            String vuelta = s.cerrada == null ? "" : "Cerrada " + Ruta.FORMATO_HORA.format(s.cerrada);
            if (s.ganaPorEntrega()) {
                int neto = s.debeEntregar();
                vuelta += (vuelta.isEmpty() ? "" : " · ") + (neto >= 0 ? "le entregó " + Tarifa.pesos(neto)
                        : "se le pagó " + Tarifa.pesos(-neto));
            } else {
                vuelta += (vuelta.isEmpty() ? "" : " · ") + "trajo " + Tarifa.pesos(s.plataATraer());
            }
            JLabel v = Tema.textoNormal(vuelta);
            v.setFont(Tema.texto(13));
            v.setForeground(Tema.GRIS_TEXTO);
            detalle.add(Tema.anchoLibre(v));
            for (int i = 0; i < s.entregas.size(); i++) {
                detalle.add(Tema.espacio(8));
                detalle.add(filaEntrega(i + 1, s.entregas.get(i)));
            }
        }
        detalle.revalidate();
        detalle.repaint();
    }

    private JComponent filaEntrega(int n, Salida.Entrega e) {
        JPanel p = columna();
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Tema.BORDE),
                BorderFactory.createEmptyBorder(8, 0, 0, 0)));
        JPanel cab = new JPanel(new BorderLayout(8, 0));
        cab.setOpaque(false);
        cab.setAlignmentX(LEFT_ALIGNMENT);
        JLabel nom = new JLabel(n + ". " + e.parada.getNombre());
        nom.setFont(Tema.textoFuerte(13.5f));
        nom.setForeground(Tema.GRIS_OSCURO);
        JLabel est = new JLabel(e.entregada() ? "Entregado" : e.noEntregada() ? "No entregado" : "Sin marcar");
        est.setFont(Tema.textoFuerte(13));
        est.setForeground(e.entregada() ? Tema.EXITO_TEXTO : e.noEntregada() ? Tema.ERROR_TEXTO : Tema.GRIS_TEXTO);
        cab.add(nom, BorderLayout.CENTER);
        cab.add(est, BorderLayout.EAST);
        cab.setMaximumSize(new Dimension(Integer.MAX_VALUE, cab.getPreferredSize().height));
        p.add(cab);
        StringBuilder b = new StringBuilder();
        if (!e.parada.getNombre().equals(e.parada.getDireccion())) {
            b.append(e.parada.getDireccion());
        }
        if (e.parada.getVentaNumero() > 0) {
            b.append(b.length() == 0 ? "" : " · ").append("Venta N.º ").append(String.format("%05d", e.parada.getVentaNumero()));
        }
        b.append(b.length() == 0 ? "" : " · ").append(e.cobrar == 0 ? "Ya pagó"
                : (e.entregada() ? "Cobró " : "Sin cobrar ") + Tarifa.pesos(e.cobrar));
        if (e.noEntregada() && !e.motivo.isEmpty()) {
            b.append("\nMotivo: ").append(e.motivo);
        }
        Tema.Parrafo d = Tema.parrafo(b.toString(), 262);
        d.setFont(Tema.texto(13));
        d.setForeground(Tema.GRIS_TEXTO);
        p.add(d);
        if (e.tieneFoto) {
            Tema.Boton foto = Tema.botonPequeno("Ver foto");
            foto.addActionListener(a -> verFoto(e, foto));
            p.add(Tema.espacio(4));
            p.add(foto);
        }
        return p;
    }

    private void verFoto(Salida.Entrega e, Tema.Boton boton) {
        if (!e.foto.isEmpty()) {
            PanelSalidas.mostrarFoto(this, e);
            return;
        }
        boton.setEnabled(false);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return app.fotoEntrega(e.id);
            }

            @Override
            protected void done() {
                boton.setEnabled(true);
                try {
                    e.foto = get();
                } catch (Exception ex) {
                    e.foto = "";
                }
                if (e.foto.isEmpty()) {
                    Aviso.mostrar(PanelHistorialSalidas.this, Aviso.Tipo.ERROR, "Foto", "No se encontró la foto");
                } else {
                    PanelSalidas.mostrarFoto(PanelHistorialSalidas.this, e);
                }
            }
        }.execute();
    }

    private static String titulo(Salida s) {
        String dia = DIA.format(s.creada).replace(".", "");
        return Character.toUpperCase(dia.charAt(0)) + dia.substring(1) + " · " + Ruta.FORMATO_HORA.format(s.creada)
                + (s.repartidor.isEmpty() ? "" : " · " + s.repartidor);
    }

    private static class Render extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean foco) {
            Salida s = (Salida) value;
            int ent = s.cuantas(Salida.Entrega.ENTREGADO);
            int no = s.cuantas(Salida.Entrega.NO_ENTREGADO);
            int fotos = 0;
            for (Salida.Entrega e : s.entregas) {
                if (e.tieneFoto) {
                    fotos++;
                }
            }
            String det = s.entregas.size() + (s.entregas.size() == 1 ? " entrega" : " entregas") + " · " + ent
                    + (ent == 1 ? " entregada" : " entregadas") + (no > 0 ? " · " + no + " no" : "")
                    + " · " + Tarifa.pesos(s.plataATraer()) + (fotos > 0 ? " · " + fotos + (fotos == 1 ? " foto" : " fotos") : "");
            return Tema.celda(list, titulo(s), Tema.textoFuerte(14), Tema.GRIS_OSCURO, det, sel, java.awt.Color.WHITE);
        }
    }
}
