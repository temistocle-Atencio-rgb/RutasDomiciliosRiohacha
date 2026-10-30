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
import rutas.logica.Avisos;
import rutas.logica.Cuadre;
import rutas.logica.Tarifa;
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

    private final Tema.Parrafo lblBase = Tema.parrafo("", 220);
    private final Tema.Parrafo lblBasePrecision = Tema.parrafo("", 220);
    private final JTextField campoBase = Tema.campo("Dirección o lugar de salida");

    private final JTextField campoCliente = Tema.campo("Cliente o referencia (opcional)");
    private final JTextField campoDireccion = Tema.campo("Dirección o lugar: Calle 15 # 10-20");
    private final DefaultListModel<Object> modeloSugerencias = new DefaultListModel<>();
    private final JList<Object> lstSugerencias = new JList<>(modeloSugerencias);
    private final JScrollPane scrollSugerencias = new JScrollPane(lstSugerencias);
    private final DefaultListModel<Parada> modeloEntregas = new DefaultListModel<>();
    private final JList<Parada> lstEntregas = new Tema.Lista<>(modeloEntregas,
            "Todavía no hay entregas. Escriba una dirección arriba o pegue la lista de pedidos.");
    private final JLabel lblEntregas = Tema.etiqueta("Entregas");

    private final JComboBox<Criterio> cbCriterio = new JComboBox<>(Criterio.values());
    private final JRadioButton rbAhora = new JRadioButton("Ahora", true);
    private final JRadioButton rbOtra = new JRadioButton("A las");
    private final JSpinner spHora = new JSpinner(new SpinnerDateModel());
    private final JLabel lblFranja = new JLabel();
    private final javax.swing.JCheckBox chkVolver = Tema.casilla("Volver a la base al terminar");
    private final javax.swing.JCheckBox chkLluvia = Tema.casilla("Está lloviendo (evitar calles destapadas)");
    private final javax.swing.JCheckBox chkUrgente = Tema.casilla("Urgente (entregar primero)");
    private final JSpinner spRepartidores = new JSpinner(new javax.swing.SpinnerNumberModel(1, 1, 10, 1));

    private final JPanel editorBase = new JPanel();
    private final Tema.Parrafo lblPromesa = Tema.parrafo("", 262);
    private Tema.Boton botonBase;
    private final Tema.Tarjeta tarjetaResultado = new Tema.Tarjeta();

    public PanelPedidos(Aplicacion app) {
        this.app = app;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 20, 16));

        add(Tema.tituloPantalla("Nueva ruta de entregas"));
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
        calcular.setToolTipText("Calcular ruta (Ctrl+Enter)");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK), "calcular");
        getActionMap().put("calcular", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                calcular();
            }
        });
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
        t.add(Tema.seccion("Punto de salida"));
        lblBase.setFont(Tema.textoFuerte(14));
        lblBase.setForeground(Tema.AZUL_PROFUNDO);
        lblBase.setAlignmentX(LEFT_ALIGNMENT);
        lblBasePrecision.setFont(Tema.texto(12.5f));
        lblBasePrecision.setForeground(Tema.GRIS_TEXTO);
        lblBasePrecision.setAlignmentX(LEFT_ALIGNMENT);
        Tema.Boton mostrar = Tema.botonPequeno("Cambiar");
        JPanel textos = new JPanel();
        textos.setOpaque(false);
        textos.setLayout(new BoxLayout(textos, BoxLayout.Y_AXIS));
        textos.add(lblBase);
        textos.add(lblBasePrecision);
        JPanel cabecera = new JPanel(new java.awt.BorderLayout(8, 0));
        cabecera.setOpaque(false);
        cabecera.setAlignmentX(LEFT_ALIGNMENT);
        cabecera.add(textos, java.awt.BorderLayout.CENTER);
        JPanel derecha = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        derecha.setOpaque(false);
        derecha.add(mostrar);
        cabecera.add(derecha, java.awt.BorderLayout.EAST);
        t.add(cabecera);

        // la salida casi nunca cambia: el formulario queda escondido hasta que se pida
        editorBase.setOpaque(false);
        editorBase.setLayout(new BoxLayout(editorBase, BoxLayout.Y_AXIS));
        editorBase.setAlignmentX(LEFT_ALIGNMENT);
        editorBase.add(Tema.espacio(8));
        Tema.anchoCompleto(campoBase);
        editorBase.add(campoBase);
        editorBase.add(Tema.espacio(6));
        Tema.Boton cambiar = Tema.botonPrimario("Usar esta dirección");
        Tema.Boton enMapa = Tema.botonSecundario("Elegir en el mapa");
        cambiar.addActionListener(e -> cambiarBase());
        campoBase.addActionListener(e -> cambiarBase());
        enMapa.addActionListener(e -> {
            app.getPanelMapa().setModo(PanelMapa.Modo.FIJAR_BASE);
            editorBase.setVisible(false);
            mostrar.setText("Cambiar");
        });
        editorBase.add(Tema.fila(8, cambiar, enMapa));
        editorBase.setVisible(false);
        mostrar.addActionListener(e -> {
            boolean ver = !editorBase.isVisible();
            editorBase.setVisible(ver);
            mostrar.setText(ver ? "Cerrar" : "Cambiar");
            if (ver) {
                campoBase.requestFocusInWindow();
            }
            revalidate();
        });
        botonBase = mostrar;
        t.add(editorBase);
        return t;
    }

    private JPanel tarjetaEntregas() {
        Tema.Tarjeta t = new Tema.Tarjeta();
        t.add(Tema.seccion("Agregar entrega"));
        Tema.anchoCompleto(campoCliente);
        Tema.anchoCompleto(campoDireccion);
        t.add(campoCliente);
        t.add(Tema.espacio(6));
        t.add(campoDireccion);

        // sugerencias mientras se escribe (clientes guardados y lugares, buscados en arboles AVL)
        lstSugerencias.setFont(Tema.texto(14));
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
        lstEntregas.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && lstEntregas.getSelectedIndex() >= 0) {
                    editarEntrega(lstEntregas.getSelectedIndex());
                }
            }

            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                menuEntrega(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                menuEntrega(e);
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
        Tema.Boton editar = Tema.botonPequeno("Editar");
        editar.setToolTipText("Cambiar el nombre o la dirección escrita (también con doble clic)");
        editar.addActionListener(e -> {
            int i = lstEntregas.getSelectedIndex();
            if (i < 0) {
                aviso("Seleccione la entrega que quiere editar.");
                return;
            }
            editarEntrega(i);
        });
        t.add(Tema.fila(6, editar, quitar, quitarTodas));
        lblPromesa.setFont(Tema.texto(14));
        lblPromesa.setForeground(Tema.AZUL_PROFUNDO);
        lblPromesa.setVisible(false);
        t.add(Tema.espacio(4));
        t.add(lblPromesa);
        return t;
    }

    private JPanel tarjetaOpciones() {
        Tema.Tarjeta t = new Tema.Tarjeta();
        t.add(Tema.seccion("Opciones"));
        t.add(Tema.etiqueta("Prioridad"));
        cbCriterio.setFont(Tema.texto(14));
        Tema.nombreAccesible(cbCriterio, "Prioridad de la ruta");
        Tema.anchoCompleto(cbCriterio);
        t.add(cbCriterio);

        t.add(Tema.etiqueta("Repartidores (motos)"));
        spRepartidores.setFont(Tema.texto(14));
        Tema.nombreAccesible(spRepartidores, "Número de repartidores");
        Tema.nombreAccesible(((JSpinner.DefaultEditor) spRepartidores.getEditor()).getTextField(), "Número de repartidores");
        spRepartidores.setPreferredSize(new Dimension(72, 32));
        JLabel ayudaMotos = new JLabel("  las entregas se reparten por zonas");
        ayudaMotos.setFont(Tema.texto(12.5f));
        ayudaMotos.setForeground(Tema.GRIS_TEXTO);
        t.add(Tema.fila(4, spRepartidores, ayudaMotos));

        t.add(Tema.etiqueta("Hora de salida"));
        JSpinner.DateEditor ed = new JSpinner.DateEditor(spHora, "hh:mm a");
        spHora.setEditor(ed);
        Tema.nombreAccesible(spHora, "Hora de salida");
        Tema.nombreAccesible(ed.getTextField(), "Hora de salida");
        spHora.setFont(Tema.texto(14));
        spHora.setPreferredSize(new Dimension(124, 32));
        ButtonGroup grupo = new ButtonGroup();
        for (JRadioButton rb : new JRadioButton[]{rbAhora, rbOtra}) {
            rb.setOpaque(false);
            rb.setFont(Tema.texto(14));
            rb.setFocusPainted(false);
            grupo.add(rb);
            rb.addActionListener(e -> actualizarFranja());
        }
        spHora.addChangeListener(e -> {
            rbOtra.setSelected(true);
            actualizarFranja();
        });
        t.add(Tema.fila(8, rbAhora, rbOtra, spHora));
        lblFranja.setFont(Tema.texto(12.5f));
        lblFranja.setAlignmentX(LEFT_ALIGNMENT);
        lblFranja.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 0));
        t.add(lblFranja);
        t.add(chkVolver);
        chkLluvia.setToolTipText("Las calles destapadas se vuelven barro: la ruta busca calles pavimentadas");
        chkLluvia.addActionListener(e -> app.setLluvia(chkLluvia.isSelected()));
        t.add(chkLluvia);
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
        lblFranja.setForeground(pico ? Tema.AVISO_TEXTO : Tema.EXITO_TEXTO);
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
            editorBase.setVisible(false);
            botonBase.setText("Cambiar");
            revalidate();
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
            p.setTelefono(c.getTelefono());
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

    /**
     * Permite reescribir el cliente y la direccion de una entrega ya agregada.
     * Se vuelve a ubicar con el texto nuevo; si es un cliente guardado se ofrece actualizarlo.
     */
    private void editarEntrega(int i) {
        Parada vieja = app.getEntregas().get(i);
        boolean conNombre = !vieja.getNombre().equals(vieja.getDireccion());
        javax.swing.JTextField cNombre = new javax.swing.JTextField(conNombre ? vieja.getNombre() : "", 26);
        javax.swing.JTextField cDir = new javax.swing.JTextField(vieja.getDireccion(), 26);
        javax.swing.JTextField cTel = new javax.swing.JTextField(vieja.getTelefono(), 26);
        cTel.setFont(Tema.texto(14));
        cNombre.setFont(Tema.texto(14));
        cDir.setFont(Tema.texto(14));
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.add(new JLabel("Cliente (opcional)"));
        form.add(cNombre);
        form.add(new JLabel("Dirección"));
        form.add(cDir);
        JLabel ayuda = new JLabel("Ej.: Calle 15 # 7-20, Cra 7 con Calle 12, La Ancha # 10-02");
        ayuda.setFont(Tema.texto(12.5f));
        ayuda.setForeground(Tema.GRIS_TEXTO);
        form.add(ayuda);
        form.add(new JLabel("Celular (opcional, para avisarle)"));
        form.add(cTel);
        javax.swing.JTextField cValor = new javax.swing.JTextField(
                vieja.getValorPedido() > 0 ? String.valueOf(vieja.getValorPedido()) : "", 26);
        cValor.setFont(Tema.texto(14));
        form.add(new JLabel("Valor del pedido en pesos (opcional, sin el domicilio)"));
        form.add(cValor);
        javax.swing.JCheckBox cPagado = new javax.swing.JCheckBox("Ya pagó por transferencia", vieja.isPagado());
        cPagado.setFont(Tema.texto(14));
        form.add(cPagado);
        javax.swing.JCheckBox cUrgente = new javax.swing.JCheckBox("Urgente (entregar primero)", vieja.isUrgente());
        cUrgente.setFont(Tema.texto(14));
        form.add(cUrgente);
        Object[] botones = {"Guardar", "Marcarla en el mapa", "Cancelar"};
        SwingUtilities.invokeLater(cDir::requestFocusInWindow);
        while (true) {
            int r = JOptionPane.showOptionDialog(this, form, "Editar entrega " + (i + 1),
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, botones, botones[0]);
            String nombre = cNombre.getText().trim();
            String dir = cDir.getText().trim();
            if (r == 1) { // el punto quedo mal: se marca en el mapa y se recuerda
                aplicarFormulario(vieja, cUrgente, cTel, cValor, cPagado);
                app.limpiarRuta();
                app.avisar();
                app.empezarCorreccion(dir.isEmpty() ? vieja.getDireccion() : dir, nombre, i);
                return;
            }
            if (r != 0) {
                return;
            }
            if (dir.isEmpty()) {
                aviso("La dirección no puede quedar vacía.");
                continue;
            }
            // si la direccion no cambio, se deja el punto donde estaba (puede venir de un cliente
            // guardado o de una correccion en el mapa) y solo se cambian los demas datos
            if (Geo.normalizar(dir).equals(Geo.normalizar(vieja.getDireccion()))) {
                Parada igual = new Parada(nombre.isEmpty() ? vieja.getDireccion() : nombre, vieja.getDireccion(),
                        vieja.getLat(), vieja.getLon());
                igual.setNodo(vieja.getNodo());
                igual.setPrecision(vieja.getPrecision());
                aplicarFormulario(igual, cUrgente, cTel, cValor, cPagado);
                app.reemplazarEntrega(i, igual);
                lstEntregas.setSelectedIndex(i);
                return;
            }
            Parada encontrada;
            try {
                encontrada = app.getGeo().ubicar(dir);
            } catch (IllegalArgumentException e) {
                Object[] op = {"Volver a escribirla", "Marcarla en el mapa", "Cancelar"};
                int o = JOptionPane.showOptionDialog(this, e.getMessage(), "Dirección no encontrada",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, op, op[0]);
                if (o == 0) {
                    continue;
                }
                if (o == 1) {
                    aplicarFormulario(vieja, cUrgente, cTel, cValor, cPagado);
                    app.limpiarRuta();
                    app.avisar();
                    app.empezarCorreccion(dir, nombre, i);
                }
                return;
            }
            Parada nueva = new Parada(nombre.isEmpty() ? encontrada.getNombre() : nombre, encontrada.getDireccion(),
                    encontrada.getLat(), encontrada.getLon());
            nueva.setNodo(encontrada.getNodo());
            nueva.setPrecision(encontrada.getPrecision());
            aplicarFormulario(nueva, cUrgente, cTel, cValor, cPagado);
            app.reemplazarEntrega(i, nueva);
            lstEntregas.setSelectedIndex(i);
            app.getPanelMapa().resaltar(nueva.getLat(), nueva.getLon(), null);
            ofrecerActualizarCliente(nombre, dir, nueva);
            return;
        }
    }

    /** Pasa a la parada lo que se escribio en el formulario de edicion (igual en todos los caminos). */
    private static void aplicarFormulario(Parada p, javax.swing.JCheckBox urgente, javax.swing.JTextField tel,
                                          javax.swing.JTextField valor, javax.swing.JCheckBox pagado) {
        p.setUrgente(urgente.isSelected());
        p.setTelefono(Avisos.extraerCelular(tel.getText()));
        p.setValorPedido(soloNumero(valor.getText()));
        p.setPagado(pagado.isSelected());
    }

    private void menuEntrega(java.awt.event.MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int i = lstEntregas.locationToIndex(e.getPoint());
        if (i < 0) {
            return;
        }
        lstEntregas.setSelectedIndex(i);
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem editar = new javax.swing.JMenuItem("Editar");
        javax.swing.JMenuItem urgente = new javax.swing.JMenuItem(
                app.getEntregas().get(i).isUrgente() ? "Quitar urgente" : "Marcar urgente");
        javax.swing.JMenuItem quitar = new javax.swing.JMenuItem("Quitar");
        editar.addActionListener(a -> editarEntrega(i));
        urgente.addActionListener(a -> {
            Parada p = app.getEntregas().get(i);
            p.setUrgente(!p.isUrgente());
            app.limpiarRuta();
            app.avisar();
        });
        quitar.addActionListener(a -> app.quitarEntrega(i));
        m.add(editar);
        m.add(urgente);
        m.add(quitar);
        m.show(lstEntregas, e.getX(), e.getY());
    }

    /** Si el nombre es de un cliente guardado con otra direccion, pregunta si se actualiza. */
    private void ofrecerActualizarCliente(String nombre, String dir, Parada p) {
        if (nombre.isEmpty()) {
            return;
        }
        Cliente c = app.getClientes().buscar(Geo.normalizar(nombre));
        if (c == null || Geo.normalizar(c.getDireccion()).equals(Geo.normalizar(dir))) {
            return;
        }
        int r = JOptionPane.showConfirmDialog(this, c.getNombre() + " tiene guardada la dirección:\n"
                + c.getDireccion() + "\n\n¿Cambiarla por " + dir + "?", "Actualizar cliente",
                JOptionPane.YES_NO_OPTION);
        if (r == JOptionPane.YES_OPTION) {
            try {
                app.guardarCliente(new Cliente(c.getNombre(), c.getTelefono(), dir, c.getNota(), p.getLat(), p.getLon()));
            } catch (java.io.IOException e) {
                aviso("No se pudo actualizar el cliente: " + e.getMessage());
            }
        }
    }

    private void terminarAgregar(Parada p) {
        p.setUrgente(chkUrgente.isSelected());
        chkUrgente.setSelected(false);
        completarTelefono(p);
        app.agregarEntrega(p);
        campoCliente.setText("");
        campoDireccion.setText("");
        app.getPanelMapa().resaltar(p.getLat(), p.getLon(), null);
        mostrarPromesa(p);
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
        area.setFont(Tema.texto(14));
        area.setLineWrap(true);
        Tema.Parrafo ayuda = Tema.parrafo("Un pedido por línea. Puede incluir el nombre del cliente antes de la dirección.\n"
                + "Ejemplos:\n    Ana María, Calle 15 # 10-20\n    Cra 7 con Calle 12 (tienda)\n"
                + "    Luis - Droguería La Economía\nEscriba URGENTE en la línea para entregarlo primero.\n"
                + "Si pone el celular (300 123 4567) se usa para avisarle por WhatsApp.\n"
                + "El valor del pedido va con signo de pesos ($25.000); escriba PAGADO si ya pagó por transferencia.", 460);
        ayuda.setForeground(Tema.GRIS_OSCURO);
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
                rutas.logica.LineaPedido lp = rutas.logica.LineaPedido.leer(l);
                boolean urgente = lp.isUrgente();
                boolean pagado = lp.isPagado();
                String celular = lp.getCelular();
                int valor = lp.getValor();
                String limpia = lp.getTexto();
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
                    nueva.setTelefono(celular);
                    nueva.setValorPedido(valor);
                    nueva.setPagado(pagado);
                    completarTelefono(nueva);
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
        t.add(Tema.seccion(rutas.size() > 1 ? "Rutas de " + rutas.size() + " repartidores" : "Ruta calculada"));
        if (rutas.size() > 1) {
            double peor = 0;
            for (int i = 0; i < rutas.size(); i++) {
                Ruta x = rutas.get(i);
                peor = Math.max(peor, x.getSegundos());
                JLabel l = new JLabel("Moto " + (i + 1) + ":  " + x.getParadas().size() + " entregas · "
                        + Geo.formatoDistancia(x.getMetros()) + " · " + Geo.formatoTiempo(x.getSegundos()));
                l.setIcon(Tema.punto(PanelMapa.colorRuta(i)));
                l.setIconTextGap(6);
                l.setFont(Tema.texto(14));
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
            Tema.nombreAccesible(cb, "Moto que se muestra");
            cb.setFont(Tema.texto(14));
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
        metricas.add(metrica("Tiempo", Geo.formatoTiempo(r.getSegundos())));
        metricas.add(metrica("Termina", r.horaFin().format(Ruta.FORMATO_HORA)));
        metricas.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        t.add(metricas);
        t.add(Tema.espacio(6));
        t.add(Tema.parrafo(r.getMetodo() + " · " + r.getCriterio() + " · salida "
                + r.getSalida().format(Ruta.FORMATO_HORA) + " (" + app.getTrafico().descripcionFranja(r.getSalida())
                + (app.getTrafico().isLluvia() ? ", con lluvia: evita destapadas" : "")
                + "). El tiempo incluye " + Math.round(app.getTrafico().tiempoEntrega() / 60) + " min por entrega.", 262));

        if (!r.getSinCamino().isEmpty()) {
            StringBuilder sb = new StringBuilder("No hay camino hasta: ");
            for (Parada p : r.getSinCamino()) {
                sb.append(p.etiqueta()).append("; ");
            }
            Tema.Parrafo l = Tema.parrafo(sb + "(revise vías cerradas o calles de una vía).", 262);
            l.setForeground(Tema.ERROR_TEXTO);
            t.add(Tema.espacio(4));
            t.add(l);
        }

        t.add(Tema.etiqueta("Orden de entrega y hora prometida"));
        for (int i = 0; i < r.getParadas().size(); i++) {
            Parada pa = r.getParadas().get(i);
            LocalTime llega = r.getLlegadas().get(i);
            int domicilio = app.domicilioDe(pa);
            String plata = domicilio <= 0 ? ""
                    : "\nDomicilio " + Tarifa.pesos(domicilio) + (pa.isPagado() ? " · ya pagó"
                    : " · cobrar " + Tarifa.pesos(app.cobrarEnPuerta(pa)));
            Tema.Parrafo l = Tema.parrafo((i + 1) + ". " + (pa.isUrgente() ? "URGENTE · " : "") + pa.etiqueta()
                    + "\n" + Avisos.textoVentana(llega) + plata, 190);
            l.setForeground(pa.isUrgente() ? Tema.ERROR_TEXTO : Tema.GRIS_OSCURO);
            Tema.Boton avisar = Tema.botonPequeno("Avisar");
            avisar.setToolTipText("Abre WhatsApp con el mensaje para el cliente");
            avisar.addActionListener(e -> avisarCliente(pa, llega));
            JPanel fila = new JPanel(new BorderLayout(6, 0));
            fila.setOpaque(false);
            fila.setAlignmentX(LEFT_ALIGNMENT);
            fila.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
            fila.add(l, BorderLayout.CENTER);
            JPanel der = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
            der.setOpaque(false);
            der.add(avisar);
            fila.add(der, BorderLayout.EAST);
            fila.setMaximumSize(new Dimension(Integer.MAX_VALUE, fila.getPreferredSize().height + 6));
            t.add(fila);
        }
        agregarPlata(t, r, rutas.size() > 1 ? "Moto " + (activa + 1) : "Ruta");

        t.add(Tema.etiqueta("Indicaciones (clic para verlas en el mapa)"));
        DefaultListModel<Indicacion> modelo = new DefaultListModel<>();
        for (Indicacion i : r.getIndicaciones()) {
            modelo.addElement(i);
        }
        JList<Indicacion> lista = new Tema.Lista<>(modelo, null);
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
        maps.addActionListener(e -> {
            abrirGoogleMaps(r);
            guardarDespachada();
        });
        whatsapp.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(
                    r.textoParaCompartir() + "\n" + r.enlaceGoogleMaps()), null);
            guardarDespachada();
            aviso("Ruta copiada. Péguela en el chat del repartidor.\nQuedó guardada en el historial.");
        });
        Tema.anchoCompleto(maps);
        t.add(maps);
        t.add(Tema.espacio(6));
        t.add(whatsapp);
        t.add(Tema.espacio(8));
        Tema.Boton matriz = Tema.botonPequeno("Ver matriz de adyacencia");
        matriz.addActionListener(e -> verMatriz(r));
        t.add(Tema.fila(6, matriz));

        t.setVisible(true);
        revalidate();
        repaint();
        SwingUtilities.invokeLater(() -> scrollRectToVisible(t.getBounds()));
    }

    private JPanel metrica(String titulo, String valor) {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(java.awt.Graphics g0) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
                Tema.suavizar(g);
                g.setColor(Tema.GRIS_CLARO);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), Tema.ARCO, Tema.ARCO);
                g.dispose();
            }
        };
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JLabel t = Tema.anchoLibre(new JLabel(titulo));
        t.setFont(Tema.textoFuerte(12.5f));
        t.setForeground(Tema.GRIS_TEXTO);
        JLabel v = new JLabel(valor);
        v.setFont(Tema.subtitulo(17));
        Tema.anchoLibre(v);
        v.setForeground(Tema.AZUL_PROFUNDO);
        p.add(t);
        p.add(v);
        return p;
    }

    /** La plata de la ruta: domicilios, pedidos y el efectivo que el repartidor debe entregar. */
    private void agregarPlata(Tema.Tarjeta t, Ruta r, String quien) {
        Cuadre c = app.cuadreDe(r);
        t.add(Tema.etiqueta("La plata de " + (quien.equals("Ruta") ? "esta ruta" : "la " + quien.toLowerCase())));
        Tema.Parrafo resumen = Tema.parrafo("Domicilios " + Tarifa.pesos(c.getDomicilios())
                + "  ·  Pedidos " + Tarifa.pesos(c.getPedidos())
                + (c.getTransferido() > 0 ? "\nYa pagado por transferencia " + Tarifa.pesos(c.getTransferido()) : ""), 300);
        resumen.setFont(Tema.texto(14));
        resumen.setForeground(Tema.GRIS_OSCURO);
        t.add(resumen);
        JLabel efectivo = Tema.anchoLibre(new JLabel("Efectivo que debe entregar: " + Tarifa.pesos(c.getEfectivo())));
        efectivo.setFont(Tema.textoFuerte(14));
        efectivo.setForeground(Tema.AZUL_PROFUNDO);
        efectivo.setAlignmentX(LEFT_ALIGNMENT);
        efectivo.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
        t.add(efectivo);
        if (c.getPedidos() == 0) {
            t.add(Tema.parrafo("Para cuadrar la caja, ponga el valor de cada pedido con Editar en la lista de entregas.", 300));
        }
        Tema.Parrafo tarifa = Tema.parrafo("Tarifa: " + app.getTarifa().descripcion()
                + ", contando la distancia por las calles desde el punto de salida.", 300);
        t.add(tarifa);
        t.add(Tema.espacio(6));
        Tema.Boton copiar = Tema.botonPequeno("Copiar cuadre");
        copiar.setToolTipText("Copia el cuadre para pegarlo en WhatsApp o en un cuaderno");
        copiar.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(
                    c.texto("Cuadre " + quien + " · " + java.time.LocalDate.now() + " · salida "
                            + r.getSalida().format(Ruta.FORMATO_HORA))), null);
            aviso("Cuadre copiado.");
        });
        Tema.Boton cambiar = Tema.botonPequeno("Cambiar tarifa");
        cambiar.addActionListener(e -> cambiarTarifa());
        t.add(Tema.fila(6, copiar, cambiar));
        t.add(Tema.espacio(4));
    }

    private void cambiarTarifa() {
        Tarifa actual = app.getTarifa();
        javax.swing.JTextField cBase = new javax.swing.JTextField(String.valueOf(actual.getBase()), 10);
        javax.swing.JTextField cKm = new javax.swing.JTextField(String.valueOf(actual.getKmIncluidos()), 10);
        javax.swing.JTextField cPorKm = new javax.swing.JTextField(String.valueOf(actual.getPorKm()), 10);
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.add(new JLabel("Valor base del domicilio (pesos)"));
        form.add(cBase);
        form.add(new JLabel("Kilómetros que cubre el valor base"));
        form.add(cKm);
        form.add(new JLabel("Valor por cada kilómetro adicional (pesos)"));
        form.add(cPorKm);
        JLabel nota = new JLabel("El total se sube a múltiplos de $500.");
        nota.setForeground(Tema.GRIS_TEXTO);
        form.add(nota);
        if (JOptionPane.showConfirmDialog(this, form, "Tarifa del domicilio", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        double km;
        try {
            km = Double.parseDouble(cKm.getText().trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            aviso("Los kilómetros deben ser un número, por ejemplo 2 o 2.5.");
            return;
        }
        int base = soloNumero(cBase.getText());
        if (base <= 0 || km < 0) {
            aviso("El valor base debe ser mayor que cero.");
            return;
        }
        app.setTarifa(new Tarifa(base, km, soloNumero(cPorKm.getText())));
        if (!app.getRutas().isEmpty()) {
            mostrarResultado(app.getRutas(), app.getRutaActiva()); // mismas rutas, plata con la tarifa nueva
        }
    }

    /** "25.000", "$25000" o "25 000" -> 25000; 0 si no hay numero. */
    private static int soloNumero(String texto) {
        String d = texto == null ? "" : texto.replaceAll("[^0-9]", "");
        if (d.isEmpty() || d.length() > 9) {
            return 0;
        }
        return Integer.parseInt(d);
    }

    /** Si la entrega no trae celular, se toma el del cliente guardado con ese nombre. */
    private void completarTelefono(Parada p) {
        if (p.getTelefono().isEmpty()) {
            Cliente c = app.buscarCliente(p.getNombre());
            if (c != null) {
                p.setTelefono(c.getTelefono());
            }
        }
    }

    /** Hora que se le puede prometer al cliente que se acaba de agregar. */
    private void mostrarPromesa(Parada p) {
        LocalTime llega = app.estimarLlegada(p, (Criterio) cbCriterio.getSelectedItem(), horaSalida(),
                chkVolver.isSelected(), (Integer) spRepartidores.getValue());
        if (llega == null) {
            lblPromesa.setVisible(false);
            return;
        }
        String quien = p.getNombre().equals(p.getDireccion()) ? "Este pedido" : p.getNombre();
        int domicilio = app.domicilioDe(p);
        lblPromesa.setText((rbAhora.isSelected() ? "Si salen ahora" : "Si salen a las "
                + horaSalida().format(Ruta.FORMATO_HORA)) + ", a " + quien + " le llega " + Avisos.textoVentana(llega)
                + (domicilio > 0 ? " · domicilio " + Tarifa.pesos(domicilio) : ""));
        lblPromesa.setVisible(true);
        revalidate();
    }

    /** Abre WhatsApp con el mensaje de "va en camino" y la hora prometida. */
    private void avisarCliente(Parada p, LocalTime llega) {
        completarTelefono(p);
        String tel = p.getTelefono();
        if (Avisos.numeroWhatsApp(tel).isEmpty()) {
            String quien = p.getNombre().equals(p.getDireccion()) ? p.getDireccion() : p.getNombre();
            String escrito = JOptionPane.showInputDialog(this, "Celular de " + quien + ":", tel);
            if (escrito == null) {
                return;
            }
            if (Avisos.numeroWhatsApp(escrito).isEmpty()) {
                aviso("Ese número no parece un celular (10 dígitos, empieza por 3).");
                return;
            }
            tel = Avisos.extraerCelular(escrito);
            p.setTelefono(tel);
            guardarTelefonoCliente(p, tel);
        }
        String negocio = app.getNegocio();
        if (negocio.isEmpty()) {
            String n = JOptionPane.showInputDialog(this,
                    "¿Cómo se llama el negocio? (sale en el mensaje, se pregunta una sola vez)", "");
            if (n != null && !n.isBlank()) {
                app.setNegocio(n);
                negocio = n.trim();
            }
        }
        String nombre = p.getNombre().equals(p.getDireccion()) ? "" : p.getNombre();
        String enlace = Avisos.enlaceWhatsApp(tel,
                Avisos.mensaje(nombre, negocio, llega, app.domicilioDe(p), app.cobrarEnPuerta(p)));
        try {
            Desktop.getDesktop().browse(new URI(enlace));
        } catch (Exception ex) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(enlace), null);
            aviso("No se pudo abrir WhatsApp. El enlace quedó copiado.");
        }
    }

    /** Si la entrega es de un cliente guardado sin celular, se le guarda; si no existe, se ofrece crearlo. */
    private void guardarTelefonoCliente(Parada p, String tel) {
        if (p.getNombre().equals(p.getDireccion())) {
            return;
        }
        Cliente c = app.buscarCliente(p.getNombre());
        try {
            if (c != null) {
                app.guardarCliente(new Cliente(c.getNombre(), tel, c.getDireccion(), c.getNota(), c.getLat(), c.getLon()));
            } else if (JOptionPane.showConfirmDialog(this, "¿Guardar a " + p.getNombre() + " como cliente con este celular?",
                    "Guardar cliente", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                app.guardarCliente(new Cliente(p.getNombre(), tel, p.getDireccion(), "", p.getLat(), p.getLon()));
            }
        } catch (java.io.IOException e) {
            aviso("No se pudo guardar el cliente: " + e.getMessage());
        }
    }

    /** Cuando la ruta se manda al repartidor queda en el historial (una sola vez por calculo). */
    private void guardarDespachada() {
        try {
            app.guardarRutaEnHistorial();
        } catch (java.io.IOException ex) {
            aviso("No se pudo guardar en el historial: " + ex.getMessage());
        }
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
        tabla.setFont(Tema.texto(12.5f));
        tabla.getTableHeader().setFont(Tema.textoFuerte(12.5f));
        tabla.setRowHeight(24);
        tabla.setEnabled(false);
        StringBuilder leyenda = new StringBuilder("Cada celda es el costo del mejor camino (Dijkstra) "
                + "de la fila a la columna. No es simétrica por las calles de una vía.\n");
        if (app.getTrafico().isLluvia()) {
            leyenda.append(tiempo ? "Está lloviendo: las calles destapadas van mucho más lento.\n"
                    : "Está lloviendo: cada calle destapada cuenta como 4 veces más larga para evitarla.\n");
        }
        for (int i = 1; i < m.tamano(); i++) {
            List<Parada> pts = app.getPlanificador().getUltimosPuntos();
            leyenda.append("E").append(i).append(" = ").append(pts.size() > i ? pts.get(i).etiqueta() : "")
                    .append("\n");
        }
        Tema.Parrafo l = Tema.parrafo(leyenda.toString().trim(), 600);
        l.setFont(Tema.texto(12.5f));
        l.setForeground(Tema.GRIS_OSCURO);
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
        lblEntregas.setText("Entregas (" + app.getEntregas().size() + ")");
        lblPromesa.setVisible(false); // se vuelve a mostrar justo despues de agregar
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
            return Tema.celda(list, (index + 1) + ". " + (p.isUrgente() ? "URGENTE · " : "") + p.etiqueta(),
                    Tema.textoFuerte(14), p.isUrgente() ? Tema.ERROR_TEXTO : Tema.AZUL_PROFUNDO,
                    p.getPrecision() + (p.getValorPedido() > 0 ? " · pedido " + Tarifa.pesos(p.getValorPedido()) : "")
                            + (p.isPagado() ? " · ya pagó" : ""), sel,
                    Color.WHITE);
        }
    }

    private static class RenderSugerencia extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean sel, boolean foco) {
            if (value instanceof Cliente) {
                Cliente c = (Cliente) value;
                return Tema.celda(list, c.getNombre(), Tema.textoFuerte(14), Tema.GRIS_OSCURO,
                        "Cliente · " + c.getDireccion(), sel, Color.WHITE);
            }
            Lugar l = (Lugar) value;
            return Tema.celda(list, l.getNombre(), Tema.texto(14), Tema.GRIS_OSCURO, l.getCategoria().toString(), sel,
                    Color.WHITE);
        }
    }

    private static class RenderIndicacion extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean sel, boolean foco) {
            Indicacion i = (Indicacion) value;
            return Tema.celda(list, i.getTexto(), i.isEsEntrega() ? Tema.textoFuerte(14) : Tema.texto(14),
                    i.isEsEntrega() ? Tema.AZUL_PROFUNDO : Tema.GRIS_OSCURO, null, sel,
                    i.isEsEntrega() ? Tema.GRIS_CLARO : Color.WHITE);
        }
    }
}
