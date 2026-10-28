package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerDateModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import rutas.estructuras.MatrizAdyacencia;
import rutas.logica.Criterio;
import rutas.modelo.Cliente;
import rutas.modelo.Geo;
import rutas.modelo.Indicacion;
import rutas.modelo.Lugar;
import rutas.modelo.Parada;
import rutas.modelo.Ruta;

/** Pestana principal: punto de salida, entregas, opciones y resultado de la ruta. */
public class PanelPedidos extends JPanel {

    private final Aplicacion app;

    private final JLabel lblBase = new JLabel();
    private final JLabel lblBasePrecision = new JLabel();
    private final JTextField campoBase = Tema.campo("Dirección o lugar de salida");

    private final JTextField campoCliente = Tema.campo("Cliente o referencia (opcional)");
    private final JTextField campoDireccion = Tema.campo("Dirección o lugar: Calle 15 # 10-20");
    private final DefaultListModel<Object> modeloSugerencias = new DefaultListModel<>();
    private final JList<Object> lstSugerencias = new JList<>(modeloSugerencias);
    private final JScrollPane scrollSugerencias = new JScrollPane(lstSugerencias);
    private final DefaultListModel<Parada> modeloEntregas = new DefaultListModel<>();
    private final JList<Parada> lstEntregas = new JList<>(modeloEntregas);
    private final JLabel lblEntregas = Tema.etiqueta("Entregas");

    private final JComboBox<Criterio> cbCriterio = new JComboBox<>(Criterio.values());
    private final JRadioButton rbAhora = new JRadioButton("Ahora", true);
    private final JRadioButton rbOtra = new JRadioButton("A las");
    private final JSpinner spHora = new JSpinner(new SpinnerDateModel());
    private final JLabel lblFranja = new JLabel();
    private final javax.swing.JCheckBox chkVolver = Tema.casilla("Volver a la base al terminar");
    private final javax.swing.JCheckBox chkUrgente = Tema.casilla("Urgente (entregar primero)");
    private final JSpinner spRepartidores = new JSpinner(new javax.swing.SpinnerNumberModel(1, 1, 10, 1));

    private final Tema.Tarjeta tarjetaResultado = new Tema.Tarjeta(Tema.AZUL_SUAVE);

    public PanelPedidos(Aplicacion app) {
        this.app = app;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 20, 16));

        add(Tema.tituloSeccion("Nueva ruta de entregas"));
        add(Tema.parrafo("Escriba las direcciones como se usan en Riohacha (Calle 15 # 10-20, "
                + "Carrera 7 con Calle 12) o el nombre de un lugar.", 272));
        add(Tema.espacio(12));
        add(tarjetaSalida());
        add(Tema.espacio(10));
        add(tarjetaEntregas());
        add(Tema.espacio(10));
        add(tarjetaOpciones());
        add(Tema.espacio(12));

        Tema.Boton calcular = Tema.botonAccion("Calcular ruta");
        Tema.anchoCompleto(calcular);
        calcular.addActionListener(e -> calcular());
        add(calcular);
        add(Tema.espacio(12));

        tarjetaResultado.setVisible(false);
        add(tarjetaResultado);

        app.alCambiar(this::refrescar);
        refrescar();
    }

    // ------------------------------------------------------------------

    private JPanel tarjetaSalida() {
        Tema.Tarjeta t = new Tema.Tarjeta();
        t.add(Tema.etiqueta("Punto de salida"));
        lblBase.setFont(Tema.textoFuerte(13));
        lblBase.setForeground(Tema.AZUL_PROFUNDO);
        lblBase.setAlignmentX(LEFT_ALIGNMENT);
        lblBasePrecision.setFont(Tema.texto(11));
        lblBasePrecision.setForeground(Tema.GRIS_TEXTO);
        lblBasePrecision.setAlignmentX(LEFT_ALIGNMENT);
        t.add(lblBase);
        t.add(lblBasePrecision);
        t.add(Tema.espacio(8));
        Tema.anchoCompleto(campoBase);
        t.add(campoBase);
        t.add(Tema.espacio(6));
        Tema.Boton cambiar = Tema.botonPrimario("Cambiar salida");
        Tema.Boton enMapa = Tema.botonSecundario("Elegir en el mapa");
        cambiar.addActionListener(e -> cambiarBase());
        campoBase.addActionListener(e -> cambiarBase());
        enMapa.addActionListener(e -> app.getPanelMapa().setModo(PanelMapa.Modo.FIJAR_BASE));
        t.add(Tema.fila(8, cambiar, enMapa));
        return t;
    }

    private JPanel tarjetaEntregas() {
        Tema.Tarjeta t = new Tema.Tarjeta();
        t.add(Tema.etiqueta("Agregar entrega"));
        Tema.anchoCompleto(campoCliente);
        Tema.anchoCompleto(campoDireccion);
        t.add(campoCliente);
        t.add(Tema.espacio(6));
        t.add(campoDireccion);

        // sugerencias mientras se escribe (clientes guardados y lugares, buscados en arboles AVL)
        lstSugerencias.setFont(Tema.texto(12));
        lstSugerencias.setCellRenderer(new RenderSugerencia());
        lstSugerencias.setVisibleRowCount(5);
        scrollSugerencias.setBorder(Tema.bordeLista());
        scrollSugerencias.setAlignmentX(LEFT_ALIGNMENT);
        scrollSugerencias.setVisible(false);
        t.add(scrollSugerencias);
        campoDireccion.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { sugerir(); }
            public void removeUpdate(DocumentEvent e) { sugerir(); }
            public void changedUpdate(DocumentEvent e) { sugerir(); }
        });
        campoDireccion.addActionListener(e -> agregarDesdeCampo());
        campoDireccion.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DOWN && scrollSugerencias.isVisible()) {
                    lstSugerencias.requestFocusInWindow();
                    lstSugerencias.setSelectedIndex(0);
                }
            }
        });
        lstSugerencias.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                usarSugerencia();
            }
        });
        lstSugerencias.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    usarSugerencia();
                }
            }
        });

        t.add(Tema.espacio(6));
        Tema.Boton agregar = Tema.botonPrimario("Agregar");
        Tema.Boton enMapa = Tema.botonSecundario("Marcar en el mapa");
        agregar.addActionListener(e -> agregarDesdeCampo());
        enMapa.addActionListener(e -> app.getPanelMapa().setModo(PanelMapa.Modo.AGREGAR_ENTREGA));
        t.add(Tema.fila(8, agregar, enMapa));
        t.add(Tema.espacio(4));
        t.add(chkUrgente);
        Tema.Boton pegar = Tema.botonPequeno("Pegar lista de pedidos");
        pegar.setToolTipText("Pegue varios pedidos de una vez, uno por línea (por ejemplo copiados de WhatsApp)");
        pegar.addActionListener(e -> pegarLista());
        t.add(Tema.espacio(2));
        t.add(pegar);

        t.add(Tema.espacio(6));
        t.add(lblEntregas);
        lstEntregas.setCellRenderer(new RenderEntrega());
        lstEntregas.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lstEntregas.addListSelectionListener(e -> {
            Parada p = lstEntregas.getSelectedValue();
            if (p != null && !e.getValueIsAdjusting()) {
                app.getPanelMapa().resaltar(p.getLat(), p.getLon(), null);
            }
        });
        JScrollPane sc = new JScrollPane(lstEntregas);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(Tema.bordeLista());
        sc.setAlignmentX(LEFT_ALIGNMENT);
        sc.setPreferredSize(new Dimension(300, 150));
        sc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        t.add(sc);
        t.add(Tema.espacio(6));
        Tema.Boton quitar = Tema.botonPequeno("Quitar");
        Tema.Boton quitarTodas = Tema.botonPequeno("Quitar todas");
        quitar.addActionListener(e -> {
            int i = lstEntregas.getSelectedIndex();
            if (i >= 0) {
                app.quitarEntrega(i);
            }
        });
        quitarTodas.addActionListener(e -> app.quitarTodas());
        Tema.Boton corregir = Tema.botonPequeno("Corregir ubicación");
        corregir.setToolTipText("Si el punto quedó mal, márquelo en el mapa. El programa recuerda la corrección.");
        corregir.addActionListener(e -> {
            int i = lstEntregas.getSelectedIndex();
            if (i < 0) {
                aviso("Seleccione la entrega que quedó mal ubicada.");
                return;
            }
            Parada p = app.getEntregas().get(i);
            app.empezarCorreccion(p.getDireccion(), p.getNombre().equals(p.getDireccion()) ? "" : p.getNombre(), i);
        });
        Tema.Boton urgente = Tema.botonPequeno("Urgente sí/no");
        urgente.addActionListener(e -> {
            int i = lstEntregas.getSelectedIndex();
            if (i >= 0) {
                Parada p = app.getEntregas().get(i);
                p.setUrgente(!p.isUrgente());
                app.limpiarRuta();
                app.avisar();
            }
        });
        t.add(Tema.fila(6, quitar, quitarTodas));
        t.add(Tema.espacio(6));
        t.add(Tema.fila(6, corregir, urgente));
        return t;
    }

    private JPanel tarjetaOpciones() {
        Tema.Tarjeta t = new Tema.Tarjeta();
        t.add(Tema.etiqueta("Prioridad"));
        cbCriterio.setFont(Tema.texto(12.5f));
        Tema.anchoCompleto(cbCriterio);
        t.add(cbCriterio);

        t.add(Tema.etiqueta("Repartidores (motos)"));
        spRepartidores.setFont(Tema.texto(12.5f));
        spRepartidores.setMaximumSize(new Dimension(70, 30));
        JLabel ayudaMotos = new JLabel("  las entregas se reparten por zonas");
        ayudaMotos.setFont(Tema.texto(11.5f));
        ayudaMotos.setForeground(Tema.GRIS_TEXTO);
        t.add(Tema.fila(4, spRepartidores, ayudaMotos));

        t.add(Tema.etiqueta("Hora de salida"));
        JSpinner.DateEditor ed = new JSpinner.DateEditor(spHora, "hh:mm a");
        spHora.setEditor(ed);
        spHora.setFont(Tema.texto(12.5f));
        spHora.setMaximumSize(new Dimension(110, 30));
        ButtonGroup grupo = new ButtonGroup();
        for (JRadioButton rb : new JRadioButton[]{rbAhora, rbOtra}) {
            rb.setOpaque(false);
            rb.setFont(Tema.texto(12.5f));
            rb.setFocusPainted(false);
            grupo.add(rb);
            rb.addActionListener(e -> actualizarFranja());
        }
        spHora.addChangeListener(e -> {
            rbOtra.setSelected(true);
            actualizarFranja();
        });
        t.add(Tema.fila(8, rbAhora, rbOtra, spHora));
        lblFranja.setFont(Tema.texto(11.5f));
        lblFranja.setAlignmentX(LEFT_ALIGNMENT);
        lblFranja.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 0));
        t.add(lblFranja);
        t.add(chkVolver);
        actualizarFranja();
        return t;
    }

    private LocalTime horaSalida() {
        if (rbAhora.isSelected()) {
            return LocalTime.now().withSecond(0).withNano(0);
        }
        Date d = (Date) spHora.getValue();
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0);
    }

    private void actualizarFranja() {
        LocalTime h = horaSalida();
        boolean pico = app.getTrafico().esHoraPico(h);
        lblFranja.setText(pico ? "● Hora pico: más trancones en vías principales y en el centro"
                : "● Tráfico normal a esa hora");
        lblFranja.setForeground(pico ? Tema.ERROR : Tema.EXITO);
    }

    // ------------------------------------------------------------------

    private void cambiarBase() {
        String texto = campoBase.getText().trim();
        if (texto.isEmpty()) {
            aviso("Escriba la dirección del negocio o elija el punto en el mapa.");
            return;
        }
        try {
            Parada p = app.getGeo().ubicar(texto);
            Parada base = new Parada("Base", p.getDireccion(), p.getLat(), p.getLon());
            base.setNodo(p.getNodo());
            base.setPrecision(p.getPrecision());
            app.setBase(base);
            campoBase.setText("");
            app.getPanelMapa().resaltar(base.getLat(), base.getLon(), null);
        } catch (IllegalArgumentException e) {
            aviso(e.getMessage());
        }
    }

    private void sugerir() {
        String texto = campoDireccion.getText().trim();
        modeloSugerencias.clear();
        if (texto.length() >= 2 && !texto.matches("(?i)^(calle|cl|cll|carrera|cra|cr|kr|k|diagonal|dg|transversal|tv|av)\\.?\\s*\\d.*")) {
            for (Cliente c : app.getClientes().buscarPorPrefijo(Geo.normalizar(texto), 3)) {
                modeloSugerencias.addElement(c);
            }
            for (Lugar l : app.getBuscador().buscar(texto, 6)) {
                modeloSugerencias.addElement(l);
            }
        }
        boolean ver = !modeloSugerencias.isEmpty();
        scrollSugerencias.setPreferredSize(new Dimension(300, Math.min(5, modeloSugerencias.size()) * 22 + 6));
        scrollSugerencias.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.min(5, modeloSugerencias.size()) * 22 + 6));
        if (scrollSugerencias.isVisible() != ver) {
            scrollSugerencias.setVisible(ver);
        }
        revalidate();
    }

    private void usarSugerencia() {
        Object o = lstSugerencias.getSelectedValue();
        if (o == null) {
            return;
        }
        String cliente = campoCliente.getText().trim();
        Parada p;
        if (o instanceof Cliente) {
            Cliente c = (Cliente) o;
            p = new Parada(c.getNombre(), c.getDireccion(), c.getLat(), c.getLon());
            p.setPrecision("Cliente guardado" + (c.getTelefono().isEmpty() ? "" : " · Tel. " + c.getTelefono()));
        } else {
            Lugar l = (Lugar) o;
            p = new Parada(cliente.isEmpty() ? l.getNombre() : cliente, l.getNombre(), l.getLat(), l.getLon());
            p.setPrecision("Lugar del mapa (" + l.getCategoria() + ")");
        }
        app.getGeo().asignarNodo(p, Geo.x(p.getLon()), Geo.y(p.getLat()), null);
        terminarAgregar(p);
    }

    private void agregarDesdeCampo() {
        String dir = campoDireccion.getText().trim();
        if (dir.isEmpty()) {
            aviso("Escriba la dirección de la entrega.");
            return;
        }
        try {
            Parada encontrada = app.getGeo().ubicar(dir);
            String cliente = campoCliente.getText().trim();
            Parada p = new Parada(cliente.isEmpty() ? encontrada.getNombre() : cliente, encontrada.getDireccion(),
                    encontrada.getLat(), encontrada.getLon());
            p.setNodo(encontrada.getNodo());
            p.setPrecision(encontrada.getPrecision());
            terminarAgregar(p);
        } catch (IllegalArgumentException e) {
            Object[] opciones = {"Marcarla en el mapa", "Corregir lo escrito"};
            int r = JOptionPane.showOptionDialog(this, e.getMessage()
                    + "\n\nSi la dirección es correcta, márquela una vez en el mapa\ny el programa la recordará la próxima vez.",
                    "Dirección no encontrada", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    opciones, opciones[0]);
            if (r == 0) {
                app.empezarCorreccion(dir, campoCliente.getText().trim(), -1);
                campoCliente.setText("");
                campoDireccion.setText("");
            }
        }
    }

    private void terminarAgregar(Parada p) {
        p.setUrgente(chkUrgente.isSelected());
        chkUrgente.setSelected(false);
        app.agregarEntrega(p);
        campoCliente.setText("");
        campoDireccion.setText("");
        app.getPanelMapa().resaltar(p.getLat(), p.getLon(), null);
        campoCliente.requestFocusInWindow();
    }

    private void calcular() {
        if (app.getEntregas().isEmpty()) {
            aviso("Agregue al menos una entrega.");
            return;
        }
        List<Ruta> rutas = app.calcular((Criterio) cbCriterio.getSelectedItem(), horaSalida(), chkVolver.isSelected(),
                (Integer) spRepartidores.getValue());
        mostrarResultado(rutas, 0);
    }

    /** Agrega varios pedidos pegados de una vez (uno por linea). */
    private void pegarLista() {
        javax.swing.JTextArea area = new javax.swing.JTextArea(12, 42);
        area.setFont(Tema.texto(12.5f));
        area.setLineWrap(true);
        JLabel ayuda = new JLabel("<html>Un pedido por línea. Puede incluir el nombre del cliente antes de la dirección.<br>"
                + "Ejemplos:<br>&nbsp;&nbsp;Ana María, Calle 15 # 10-20<br>&nbsp;&nbsp;Cra 7 con Calle 12 (tienda)"
                + "<br>&nbsp;&nbsp;Luis - Droguería La Economía<br>Escriba URGENTE en la línea para entregarlo primero.</html>");
        ayuda.setFont(Tema.texto(12));
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(ayuda, BorderLayout.NORTH);
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        while (true) {
            int r = JOptionPane.showConfirmDialog(this, p, "Pegar lista de pedidos", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return;
            }
            StringBuilder fallidas = new StringBuilder();
            int agregadas = 0;
            for (String linea : area.getText().split("\\R")) {
                String l = linea.trim();
                if (l.isEmpty()) {
                    continue;
                }
                boolean urgente = l.toLowerCase().contains("urgente");
                String limpia = l.replaceAll("(?i)urgente", "").replaceAll("^[\\s,;:\\-]+|[\\s,;:\\-]+$", "");
                try {
                    Parada encontrada = app.getGeo().ubicar(limpia);
                    String cliente = nombreDelCliente(limpia);
                    // la direccion sin el nombre del cliente adelante
                    String direccion = cliente.isEmpty() ? limpia
                            : limpia.substring(limpia.indexOf(cliente) + cliente.length()).replaceAll("^[\\s,;:\\-]+", "");
                    if (direccion.isEmpty()) {
                        direccion = limpia;
                    }
                    Parada nueva = new Parada(cliente.isEmpty() ? direccion : cliente,
                            direccion, encontrada.getLat(), encontrada.getLon());
                    nueva.setNodo(encontrada.getNodo());
                    nueva.setPrecision(encontrada.getPrecision());
                    nueva.setUrgente(urgente);
                    app.agregarEntrega(nueva);
                    agregadas++;
                } catch (IllegalArgumentException e) {
                    fallidas.append(l).append("\n");
                }
            }
            if (fallidas.length() == 0) {
                aviso(agregadas + " pedidos agregados.");
                return;
            }
            // se dejan en el cuadro solo las que fallaron para corregirlas
            area.setText(fallidas.toString());
            aviso(agregadas + " pedidos agregados. Estas direcciones no se encontraron; corríjalas o agréguelas "
                    + "una por una con \"Marcarla en el mapa\":\n\n" + fallidas);
        }
    }

    /** Lo que esta antes de la direccion es el nombre del cliente: "Ana María, Calle 15 # 10-20" -> "Ana María". */
    private static String nombreDelCliente(String linea) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)(^|[\\s,;:\\-])(calle|cll|cl|carrera|cra|cr|kr|diagonal|dg|transversal|tv|avenida|av)\\.?\\s*\\d").matcher(linea);
        if (m.find() && m.start() > 0) {
            return linea.substring(0, m.start()).replaceAll("[\\s,;:\\-]+$", "").trim();
        }
        int guion = linea.indexOf(" - ");
        if (guion > 0) {
            return linea.substring(0, guion).trim();
        }
        return "";
    }

    // ------------------------------------------------------------------
    //  Resultado
    // ------------------------------------------------------------------

    private void mostrarResultado(List<Ruta> rutas, int activa) {
        Ruta r = rutas.get(activa);
        Tema.Tarjeta t = tarjetaResultado;
        t.removeAll();
        t.add(Tema.tituloSeccion(rutas.size() > 1 ? "Rutas de " + rutas.size() + " repartidores" : "Ruta calculada"));
        if (rutas.size() > 1) {
            double peor = 0;
            for (int i = 0; i < rutas.size(); i++) {
                Ruta x = rutas.get(i);
                peor = Math.max(peor, x.getSegundos());
                Color c = PanelMapa.colorRuta(i);
                String hex = String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
                JLabel l = new JLabel("<html><span style='color:" + hex + "'>●</span> <b>Moto " + (i + 1) + ":</b> "
                        + x.getParadas().size() + " entregas · " + Geo.formatoDistancia(x.getMetros()) + " · "
                        + Geo.formatoTiempo(x.getSegundos()) + "</html>");
                l.setFont(Tema.texto(12));
                l.setForeground(Tema.GRIS_OSCURO);
                l.setAlignmentX(LEFT_ALIGNMENT);
                t.add(l);
            }
            t.add(Tema.parrafo("Todas terminan en máximo " + Geo.formatoTiempo(peor) + ".", 262));
            t.add(Tema.etiqueta("Ver detalle de"));
            String[] nombres = new String[rutas.size()];
            for (int i = 0; i < nombres.length; i++) {
                nombres[i] = "Moto " + (i + 1);
            }
            JComboBox<String> cb = new JComboBox<>(nombres);
            cb.setFont(Tema.texto(12.5f));
            cb.setSelectedIndex(activa);
            Tema.anchoCompleto(cb);
            cb.addActionListener(e -> {
                app.setRutaActiva(cb.getSelectedIndex());
                SwingUtilities.invokeLater(() -> mostrarResultado(rutas, cb.getSelectedIndex()));
            });
            t.add(cb);
            t.add(Tema.espacio(8));
        }

        JPanel metricas = new JPanel(new GridLayout(1, 3, 8, 0));
        metricas.setOpaque(false);
        metricas.setAlignmentX(LEFT_ALIGNMENT);
        metricas.add(metrica("Distancia", Geo.formatoDistancia(r.getMetros())));
        metricas.add(metrica("Tiempo total", Geo.formatoTiempo(r.getSegundos())));
        metricas.add(metrica("Termina", r.horaFin().format(Ruta.FORMATO_HORA)));
        metricas.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        t.add(metricas);
        t.add(Tema.espacio(6));
        t.add(Tema.parrafo(r.getMetodo() + " · " + r.getCriterio() + " · salida "
                + r.getSalida().format(Ruta.FORMATO_HORA) + " (" + app.getTrafico().descripcionFranja(r.getSalida())
                + "). El tiempo incluye " + Math.round(app.getTrafico().tiempoEntrega() / 60) + " min por entrega.", 262));

        if (!r.getSinCamino().isEmpty()) {
            StringBuilder sb = new StringBuilder("No hay camino hasta: ");
            for (Parada p : r.getSinCamino()) {
                sb.append(p.etiqueta()).append("; ");
            }
            JLabel l = Tema.parrafo(sb + "(revise vías cerradas o calles de una vía).", 262);
            l.setForeground(Tema.ERROR);
            t.add(Tema.espacio(4));
            t.add(l);
        }

        t.add(Tema.etiqueta("Orden de entrega"));
        for (int i = 0; i < r.getParadas().size(); i++) {
            JLabel l = new JLabel("<html><b>" + (i + 1) + ".</b> "
                    + (r.getParadas().get(i).isUrgente() ? "<b style='color:#D64545'>URGENTE</b> " : "")
                    + escapar(r.getParadas().get(i).etiqueta())
                    + " <span style='color:#5B6675'>· " + r.getLlegadas().get(i).format(Ruta.FORMATO_HORA) + "</span></html>");
            l.setFont(Tema.texto(12));
            l.setForeground(Tema.GRIS_OSCURO);
            l.setAlignmentX(LEFT_ALIGNMENT);
            l.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
            t.add(l);
        }

        t.add(Tema.etiqueta("Indicaciones (clic para verlas en el mapa)"));
        DefaultListModel<Indicacion> modelo = new DefaultListModel<>();
        for (Indicacion i : r.getIndicaciones()) {
            modelo.addElement(i);
        }
        JList<Indicacion> lista = new JList<>(modelo);
        lista.setCellRenderer(new RenderIndicacion());
        lista.addListSelectionListener(e -> {
            Indicacion i = lista.getSelectedValue();
            if (i != null && !e.getValueIsAdjusting()) {
                app.getPanelMapa().mostrarPasoIndicacion(i.getNodo());
            }
        });
        JScrollPane sc = new JScrollPane(lista);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(Tema.bordeLista());
        sc.setAlignmentX(LEFT_ALIGNMENT);
        sc.setPreferredSize(new Dimension(300, 220));
        sc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
        t.add(sc);
        t.add(Tema.espacio(10));

        Tema.Boton maps = Tema.botonPrimario("Abrir en Google Maps");
        Tema.Boton whatsapp = Tema.botonSecundario("Copiar ruta para WhatsApp");
        Tema.anchoCompleto(whatsapp);
        maps.addActionListener(e -> abrirGoogleMaps(r));
        whatsapp.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(
                    r.textoParaCompartir() + "\n" + r.enlaceGoogleMaps()), null);
            aviso("Ruta copiada. Péguela en el chat del repartidor.");
        });
        Tema.anchoCompleto(maps);
        t.add(maps);
        t.add(Tema.espacio(6));
        t.add(whatsapp);
        t.add(Tema.espacio(8));
        Tema.Boton guardar = Tema.botonPequeno("Guardar en historial");
        Tema.Boton matriz = Tema.botonPequeno("Ver matriz de adyacencia");
        guardar.addActionListener(e -> {
            try {
                app.guardarRutaEnHistorial();
                aviso("Ruta guardada en el historial.");
            } catch (java.io.IOException ex) {
                aviso("No se pudo guardar: " + ex.getMessage());
            }
        });
        matriz.addActionListener(e -> verMatriz(r));
        t.add(Tema.fila(6, guardar, matriz));

        t.setVisible(true);
        revalidate();
        repaint();
        SwingUtilities.invokeLater(() -> scrollRectToVisible(t.getBounds()));
    }

    private static String escapar(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JPanel metrica(String titulo, String valor) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JLabel t = new JLabel(titulo.toUpperCase());
        t.setFont(Tema.textoFuerte(9.5f));
        t.setForeground(Tema.GRIS_TEXTO);
        JLabel v = new JLabel(valor);
        v.setFont(Tema.titulo(15));
        v.setForeground(Tema.AZUL);
        p.add(t);
        p.add(v);
        return p;
    }

    private void abrirGoogleMaps(Ruta r) {
        try {
            Desktop.getDesktop().browse(new URI(r.enlaceGoogleMaps()));
        } catch (Exception ex) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(r.enlaceGoogleMaps()), null);
            aviso("No se pudo abrir el navegador. El enlace quedó copiado.");
        }
    }

    /** Muestra la matriz de adyacencia entre paradas (minutos o metros). */
    private void verMatriz(Ruta r) {
        MatrizAdyacencia m = app.getPlanificador().getUltimaMatriz();
        if (m == null) {
            return;
        }
        List<String> nombres = new ArrayList<>();
        nombres.add("Base");
        for (int i = 1; i < m.tamano(); i++) {
            nombres.add("E" + i);
        }
        boolean tiempo = r.getCriterio().equals(Criterio.MAS_RAPIDA.toString());
        String[] columnas = new String[m.tamano() + 1];
        columnas[0] = tiempo ? "De \\ A (min)" : "De \\ A (km)";
        for (int j = 0; j < m.tamano(); j++) {
            columnas[j + 1] = nombres.get(j);
        }
        Object[][] datos = new Object[m.tamano()][m.tamano() + 1];
        for (int i = 0; i < m.tamano(); i++) {
            datos[i][0] = nombres.get(i);
            for (int j = 0; j < m.tamano(); j++) {
                double v = m.get(i, j);
                datos[i][j + 1] = !m.hayCamino(i, j) ? "∞"
                        : tiempo ? String.format("%.1f", v / 60) : String.format("%.2f", v / 1000);
            }
        }
        JTable tabla = new JTable(datos, columnas);
        tabla.setFont(Tema.texto(12));
        tabla.getTableHeader().setFont(Tema.textoFuerte(11.5f));
        tabla.setRowHeight(24);
        tabla.setEnabled(false);
        StringBuilder leyenda = new StringBuilder("<html>Cada celda es el costo del mejor camino (Dijkstra) "
                + "de la fila a la columna. No es simétrica por las calles de una vía.<br>");
        for (int i = 1; i < m.tamano(); i++) {
            List<Parada> pts = app.getPlanificador().getUltimosPuntos();
            leyenda.append("E").append(i).append(" = ").append(escapar(pts.size() > i ? pts.get(i).etiqueta() : ""))
                    .append("<br>");
        }
        JLabel l = new JLabel(leyenda + "</html>");
        l.setFont(Tema.texto(11.5f));
        l.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(tabla), BorderLayout.CENTER);
        p.add(l, BorderLayout.SOUTH);
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), "Matriz de adyacencia entre paradas");
        d.setContentPane(p);
        d.setSize(Math.min(900, 140 + 70 * m.tamano()), Math.min(700, 220 + 26 * m.tamano() + 18 * m.tamano()));
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    // ------------------------------------------------------------------

    private void refrescar() {
        Parada b = app.getBase();
        lblBase.setText(b.getDireccion());
        lblBasePrecision.setText(b.getPrecision());
        int sel = lstEntregas.getSelectedIndex();
        modeloEntregas.clear();
        for (Parada p : app.getEntregas()) {
            modeloEntregas.addElement(p);
        }
        if (sel >= 0 && sel < modeloEntregas.size()) {
            lstEntregas.setSelectedIndex(sel);
        }
        lblEntregas.setText(("Entregas (" + app.getEntregas().size() + ")").toUpperCase());
        if (app.getRuta() == null) {
            tarjetaResultado.setVisible(false);
        }
        revalidate();
        repaint();
    }

    private void aviso(String msg) {
        JOptionPane.showMessageDialog(this, msg, "DomiRuta", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Muestra la ruta en el panel (por ejemplo desde el historial). */
    public void mostrar(Ruta r) {
        mostrarResultado(List.of(r), 0);
    }

    // ------------------------------------------------------------------
    //  Como se ven las filas de las listas
    // ------------------------------------------------------------------

    private static class RenderEntrega extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean sel, boolean foco) {
            Parada p = (Parada) value;
            String html = "<html><div style='width:250px'><b style='color:#003A73'>" + (index + 1) + ".</b> "
                    + (p.isUrgente() ? "<b style='color:#D64545'>URGENTE</b> " : "") + escapar(p.etiqueta())
                    + "<br><span style='color:#5B6675;font-size:9px'>" + escapar(p.getPrecision()) + "</span></div></html>";
            JLabel l = (JLabel) super.getListCellRendererComponent(list, html, index, sel, foco);
            l.setFont(Tema.texto(12));
            l.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            if (sel) {
                l.setBackground(Tema.AZUL_SUAVE);
                l.setForeground(Tema.GRIS_OSCURO);
            }
            return l;
        }
    }

    private static class RenderSugerencia extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean sel, boolean foco) {
            String texto;
            if (value instanceof Cliente) {
                texto = "<html><b>" + escapar(((Cliente) value).getNombre()) + "</b> <span style='color:#5B6675'>· cliente · "
                        + escapar(((Cliente) value).getDireccion()) + "</span></html>";
            } else {
                Lugar l = (Lugar) value;
                texto = "<html>" + escapar(l.getNombre()) + " <span style='color:#5B6675'>· " + l.getCategoria() + "</span></html>";
            }
            JLabel l = (JLabel) super.getListCellRendererComponent(list, texto, index, sel, foco);
            l.setFont(Tema.texto(12));
            l.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            if (sel) {
                l.setBackground(Tema.AZUL_SUAVE);
                l.setForeground(Tema.GRIS_OSCURO);
            }
            return l;
        }
    }

    private static class RenderIndicacion extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean sel, boolean foco) {
            Indicacion i = (Indicacion) value;
            JLabel l = (JLabel) super.getListCellRendererComponent(list,
                    "<html><div style='width:250px'>" + escapar(i.getTexto()) + "</div></html>", index, sel, foco);
            l.setFont(i.isEsEntrega() ? Tema.textoFuerte(12) : Tema.texto(12));
            l.setForeground(i.isEsEntrega() ? Tema.AZUL_PROFUNDO : Tema.GRIS_OSCURO);
            l.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            if (i.isEsEntrega()) {
                l.setBackground(sel ? Tema.AZUL_SUAVE : new Color(0xFFF4E6));
            } else if (sel) {
                l.setBackground(Tema.AZUL_SUAVE);
            }
            return l;
        }
    }
}
