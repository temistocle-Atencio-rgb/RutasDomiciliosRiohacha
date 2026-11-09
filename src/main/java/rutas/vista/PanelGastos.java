package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import rutas.logica.BaseGastos;
import rutas.logica.MediosPago;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.modelo.MetodoPago;
import rutas.modelo.Proveedor;
import rutas.modelo.Usuario;

/**
 * Gastos › Historial de gastos (como en PuveSoft): fechas con hora, usuario, proveedor, método de
 * pago y tipo (contado o crédito). Nuevo gasto, abonos a los de crédito y anular con motivo.
 * Lo pagado en efectivo puede salir de la caja (la casilla «Sale de la caja»).
 */
public class PanelGastos extends ListaGastos<BaseGastos.Gasto> {

    static final String[] COLUMNAS = {"Item", "Fecha y Hora", "Descripción del Gasto", "Categoría", "Proveedor",
        "Responsable", "Método de pago", "Valor", "Tipo", "Abonado", "Saldo", "Egreso de Caja", "Estado", "Acción"};
    private static final int C_VALOR = 7, C_ABONADO = 9, C_SALDO = 10, C_CAJA = 11, C_ESTADO = 12, C_ACCION = 13;
    private static final String TODOS = "TODOS";

    private final Controles.CampoFecha fechaInicial = new Controles.CampoFecha("Fecha inicial");
    private final Controles.CampoFecha fechaFinal = new Controles.CampoFecha("Fecha final");
    private final JComboBox<LocalTime> horaInicial = comboHoras(false);
    private final JComboBox<LocalTime> horaFinal = comboHoras(true);
    private final JComboBox<Object> comboUsuario = new JComboBox<>();
    private final JComboBox<Object> comboProveedor = new JComboBox<>();
    private final JComboBox<Object> comboMetodo = new JComboBox<>();
    private final JComboBox<String> comboTipo = new JComboBox<>(new String[]{TODOS, "CONTADO", "CRÉDITO"});

    public PanelGastos(Aplicacion app) {
        super(app, "Historial de Gastos", COLUMNAS, new int[]{44, 104, 190, 120, 120, 100, 100, 84, 88, 80, 80, 84, 100, 76},
                "No hay registros", "Cambie las fechas o los filtros y oprima «Consultar».", "notas");
        armar();
        alMostrar();
        consultar();
    }

    @Override
    protected JComponent filtros() {
        fechaInicial.setFecha(LocalDate.now().withDayOfMonth(1));
        fechaFinal.setFecha(LocalDate.now());
        for (JComboBox<?> c : List.of(comboUsuario, comboProveedor, comboMetodo)) {
            @SuppressWarnings("unchecked")
            JComboBox<Object> o = (JComboBox<Object>) c;
            Tema.estiloCombo(o, PanelGastos::nombre);
        }
        Tema.estiloCombo(comboTipo, String::valueOf);
        comboUsuario.getAccessibleContext().setAccessibleName("Usuario");
        comboProveedor.getAccessibleContext().setAccessibleName("Proveedor");
        comboMetodo.getAccessibleContext().setAccessibleName("Método de pago");
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
        f.add(apilados("Usuario", comboUsuario, "Proveedor", comboProveedor), g);
        f.add(apilados("Método de pago", comboMetodo, "Tipo de gasto", comboTipo), g);
        Tema.Boton consultar = Tema.botonPrimario("Consultar");
        consultar.setIcon(Iconos.de("lupa", 16, Tema.BLANCO));
        consultar.setToolTipText("Mostrar los gastos con estos filtros (Enter)");
        consultar.addActionListener(e -> consultar());
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        f.add(conLeyenda(consultar, null), g);
        g.weightx = 0.2;
        f.add(Box.createHorizontalGlue(), g);
        Tema.Boton fijos = Tema.botonSecundario("Fijos");
        fijos.setToolTipText("Arriendo, servicios y lo que se paga cada mes");
        fijos.addActionListener(e -> VentanaPrincipal.irA(this, "Gastos fijos"));
        Tema.Boton abonos = Tema.botonSecundario("Abonos");
        abonos.setToolTipText("Lo que se ha abonado a los gastos a crédito");
        abonos.addActionListener(e -> VentanaPrincipal.irA(this, "Abonos de gastos"));
        Tema.Boton nuevo = Tema.botonAccion("Nuevo Gasto");
        nuevo.setToolTipText("Registrar un gasto (Ctrl+N)");
        nuevo.addActionListener(e -> nuevoGasto());
        registerKeyboardAction(e -> nuevoGasto(), javax.swing.KeyStroke.getKeyStroke("ctrl N"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        g.insets = new Insets(0, 0, 0, 8);
        f.add(conLeyenda(fijos, "Gastos fijos"), g);
        f.add(conLeyenda(abonos, "Historial Abonos"), g);
        g.insets = new Insets(0, 0, 0, 0);
        f.add(conLeyenda(nuevo, null), g);
        return f;
    }

    static String nombre(Object o) {
        if (o instanceof Usuario) {
            return ((Usuario) o).getNombre();
        }
        if (o instanceof Proveedor) {
            return ((Proveedor) o).nombre;
        }
        return String.valueOf(o);
    }

    @Override
    protected void alMostrar() {
        try {
            rellenar(comboUsuario, new ArrayList<>(app.getTienda().listarUsuarios()));
            rellenar(comboProveedor, new ArrayList<>(app.getOrdenes().listarProveedores(false)));
            rellenar(comboMetodo, new ArrayList<>(MediosPago.activos(app.getTienda())));
        } catch (Exception e) {
            Ubicaciones.registrarError(e);
        }
    }

    /** Pone «TODOS» y la lista, y deja escogido el que estaba (por nombre). */
    private static void rellenar(JComboBox<Object> c, List<Object> l) {
        String antes = c.getSelectedItem() == null ? TODOS : nombre(c.getSelectedItem());
        c.removeAllItems();
        c.addItem(TODOS);
        for (Object o : l) {
            c.addItem(o);
            if (nombre(o).equals(antes)) {
                c.setSelectedItem(o);
            }
        }
    }

    @Override
    protected List<BaseGastos.Gasto> leer() throws Exception {
        BaseGastos.Filtro f = new BaseGastos.Filtro();
        f.desde = momento(fechaInicial, horaInicial);
        f.hasta = momento(fechaFinal, horaFinal);
        Object u = comboUsuario.getSelectedItem(), p = comboProveedor.getSelectedItem(), m = comboMetodo.getSelectedItem();
        f.usuarioId = u instanceof Usuario ? ((Usuario) u).getId() : 0;
        f.proveedorId = p instanceof Proveedor ? ((Proveedor) p).id : 0;
        f.metodo = m instanceof MetodoPago ? (MetodoPago) m : null;
        String t = (String) comboTipo.getSelectedItem();
        f.credito = "CONTADO".equals(t) ? Boolean.FALSE : "CRÉDITO".equals(t) ? Boolean.TRUE : null;
        return app.getGastos().gastos(f);
    }

    @Override
    protected Object valor(BaseGastos.Gasto g, int c) {
        switch (c) {
            case 1:
                return PanelHistorialCompras.textoFecha(g.fecha);
            case 2:
                return g.descripcion.isEmpty() ? "—" : g.descripcion;
            case 3:
                return g.categoria;
            case 4:
                return g.proveedor.isEmpty() ? "Sin proveedor" : g.proveedor;
            case 5:
                return g.responsable;
            case 6:
                return g.credito ? "—" : g.metodo.toString();
            case C_VALOR:
                return Tarifa.pesos(g.valor);
            case 8:
                return g.credito ? "CRÉDITO" : "CONTADO";
            case C_ABONADO:
                return g.credito ? Tarifa.pesos(g.abonado) : "—";
            case C_SALDO:
                return g.credito ? Tarifa.pesos(g.saldo()) : "—";
            case C_CAJA:
                return g.egresoCaja() > 0 ? Tarifa.pesos(g.egresoCaja()) : "No";
            case C_ESTADO:
                return g.estado();
            default:
                return "";
        }
    }

    @Override
    protected List<Accion> acciones(BaseGastos.Gasto g, int c) {
        if (c != C_ACCION) {
            return List.of();
        }
        List<Accion> l = new ArrayList<>();
        l.add(new Accion(PanelProductos.Icono.LUPA, null, Tema.AZUL, "Ver el gasto" + (g.saldo() > 0 ? " y abonar" : ""),
                () -> verDetalle(g)));
        if (!g.anulado) {
            l.add(new Accion(PanelProductos.Icono.PAPELERA, null, Tema.ERROR, "Anular el gasto", () -> anular(g)));
        }
        return l;
    }

    @Override
    protected Color color(BaseGastos.Gasto g, int c) {
        if (c == C_ESTADO) {
            return g.anulado ? Tema.ERROR_TEXTO : g.saldo() > 0 ? Tema.AVISO_TEXTO : Tema.EXITO_TEXTO;
        }
        if (g.anulado || (c == 4 && g.proveedor.isEmpty()) || "—".equals(valor(g, c)) || (c == C_CAJA && g.egresoCaja() == 0)) {
            return Tema.GRIS_TEXTO;
        }
        return null;
    }

    @Override
    protected int minimo(int c, int ancho) {
        return c == 8 ? ancho : c == 1 ? 88 : super.minimo(c, ancho); // CONTADO / CRÉDITO y la fecha completos
    }

    @Override
    protected boolean negrita(BaseGastos.Gasto g, int c) {
        return c == C_VALOR || c == C_ESTADO || (c == C_SALDO && g.saldo() > 0);
    }

    @Override
    protected int alineacion(int c) {
        return c == 2 || c == 4 ? SwingConstants.LEFT
                : c == C_VALOR || c == C_ABONADO || c == C_SALDO || c == C_CAJA ? SwingConstants.RIGHT : SwingConstants.CENTER;
    }

    @Override
    protected String resumen(List<BaseGastos.Gasto> l) {
        long total = 0, saldo = 0, caja = 0;
        int n = 0;
        for (BaseGastos.Gasto g : l) {
            if (!g.anulado) {
                n++;
                total += g.valor;
                saldo += g.saldo();
                caja += g.egresoCaja();
            }
        }
        return n + (n == 1 ? " gasto" : " gastos") + "    ·    Total: " + Tarifa.pesos(total)
                + "    ·    Por pagar: " + Tarifa.pesos(saldo) + "    ·    Salió de la caja: " + Tarifa.pesos(caja);
    }

    @Override
    protected void abrir(BaseGastos.Gasto g) {
        verDetalle(g);
    }

    @Override
    protected String subtituloPdf() {
        LocalDate a = fechaInicial.fechaSiEsValida(), b = fechaFinal.fechaSiEsValida();
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        return a == null || b == null ? "" : a.equals(b) ? a.format(f) : a.format(f) + " al " + b.format(f);
    }

    // ------------------------------------------------------------------
    //  Partes del formulario (tambien las usa Gastos fijos)
    // ------------------------------------------------------------------

    /** Combo con un boton «+» al lado (crear otra categoria o proveedor sin salir). */
    static JComponent conMas(JComboBox<?> combo, String ayuda, Runnable mas) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setOpaque(false);
        p.add(combo, BorderLayout.CENTER);
        Tema.Boton b = Tema.botonSecundario("+");
        b.setFont(Tema.subtitulo(17));
        b.setToolTipText(ayuda);
        Tema.nombreAccesible(b, ayuda);
        b.addActionListener(e -> mas.run());
        p.add(b, BorderLayout.EAST);
        p.setAlignmentX(LEFT_ALIGNMENT);
        Tema.anchoCompleto(p);
        return p;
    }

    static void llenarCategorias(Aplicacion app, JComboBox<Object> c, long escoger) throws java.sql.SQLException {
        c.removeAllItems();
        c.addItem("Seleccione una categoría");
        for (BaseGastos.Categoria k : app.getGastos().categorias(true)) {
            c.addItem(k);
            if (k.id == escoger) {
                c.setSelectedItem(k);
            }
        }
    }

    static void llenarProveedores(Aplicacion app, JComboBox<Object> c, long escoger) throws java.sql.SQLException {
        c.removeAllItems();
        c.addItem("Sin proveedor");
        for (Proveedor p : app.getOrdenes().listarProveedores(true)) {
            c.addItem(p);
            if (p.id == escoger) {
                c.setSelectedItem(p);
            }
        }
    }

    static void llenarMetodos(Aplicacion app, JComboBox<Object> c, MetodoPago escoger) throws java.sql.SQLException {
        c.removeAllItems();
        c.addItem("Seleccione el método de pago");
        for (MetodoPago m : MediosPago.activos(app.getTienda())) {
            c.addItem(m);
        }
        if (escoger != null) {
            c.setSelectedItem(escoger);
        }
    }

    /** Pide un nombre en una tarjeta pequeña encima (para el «+»). */
    static void pedirNombre(JComponent dentro, String titulo, String ayuda, java.util.function.Consumer<String> guardar) {
        JPanel t = Capa.tarjeta(400, 24, 28);
        t.add(Tema.tituloPantalla(titulo));
        t.add(Tema.espacio(12));
        JTextField campo = Tema.campo(ayuda);
        campo.getAccessibleContext().setAccessibleName(ayuda);
        campo.setAlignmentX(LEFT_ALIGNMENT);
        Tema.anchoCompleto(campo);
        t.add(campo);
        JLabel error = errorLabel();
        t.add(Tema.espacio(4));
        t.add(error);
        t.add(Tema.espacio(8));
        Tema.Boton si = Tema.botonPrimario("Guardar");
        Tema.Boton no = Tema.botonSecundario("Cancelar");
        JPanel f = Tema.fila(8, no, si);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        Capa capa = new Capa(dentro, t, true);
        Runnable hacer = () -> {
            try {
                guardar.accept(campo.getText());
                capa.cerrar();
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
            }
        };
        si.addActionListener(e -> hacer.run());
        campo.addActionListener(e -> hacer.run());
        no.addActionListener(e -> capa.cerrar());
        capa.mostrar(campo);
    }

    static JLabel errorLabel() {
        JLabel error = new JLabel(" ");
        error.setFont(Tema.texto(12.5f));
        error.setForeground(Tema.ERROR_TEXTO);
        error.setAlignmentX(LEFT_ALIGNMENT);
        return error;
    }

    /** Pesos escritos con o sin puntos ni «$»: «1.250.000», «$ 45000». */
    static long pesos(String texto) {
        String d = texto == null ? "" : texto.replaceAll("\\D", "");
        if (d.isEmpty()) {
            return 0;
        }
        if (d.length() > 12) {
            throw new IllegalArgumentException("El valor es muy grande: revíselo");
        }
        return Long.parseLong(d);
    }

    static void agregar(JPanel t, String etiqueta, JComponent c) {
        t.add(etiqueta(etiqueta));
        t.add(Tema.espacio(5));
        c.setAlignmentX(LEFT_ALIGNMENT);
        Tema.anchoCompleto(c);
        t.add(c);
        t.add(Tema.espacio(10));
    }

    static void nuevaCategoria(Aplicacion app, JComponent dentro, JComboBox<Object> combo) {
        pedirNombre(dentro, "Nueva categoría", "Nombre de la categoría (ej. ASEO)", n -> {
            try {
                BaseGastos.Categoria k = new BaseGastos.Categoria();
                k.nombre = n;
                app.getGastos().guardarCategoria(k);
                llenarCategorias(app, combo, k.id);
            } catch (java.sql.SQLException ex) {
                throw new IllegalArgumentException("No se pudo guardar: " + ex.getMessage());
            }
        });
    }

    static void nuevoProveedor(Aplicacion app, JComponent dentro, JComboBox<Object> combo) {
        pedirNombre(dentro, "Nuevo proveedor", "Nombre (ej. Air-e, Arrendamientos La Guajira)", n -> {
            try {
                Proveedor p = new Proveedor();
                p.nombre = n;
                app.getOrdenes().guardarProveedor(p);
                llenarProveedores(app, combo, p.id);
            } catch (java.sql.SQLException ex) {
                throw new IllegalArgumentException("No se pudo guardar: " + ex.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    //  Agregar nuevo gasto
    // ------------------------------------------------------------------

    void nuevoGasto() {
        if (Capa.hayAbierta(this)) {
            return;
        }
        JPanel t = Capa.tarjeta(520, 22, 30);
        t.add(Tema.tituloPantalla("Agregar Nuevo Gasto"));
        t.add(Tema.espacio(12));
        Controles.CampoFecha fecha = new Controles.CampoFecha("Fecha del gasto");
        fecha.setFecha(LocalDate.now());
        JComboBox<LocalTime> hora = new JComboBox<>();
        LocalTime ahora = LocalTime.now().withSecond(0).withNano(0);
        hora.addItem(ahora);
        for (int m = 0; m < 24 * 60; m += 30) {
            hora.addItem(LocalTime.of(m / 60, m % 60));
        }
        Tema.estiloCombo(hora, PanelHistorialCompras::textoHora);
        hora.getAccessibleContext().setAccessibleName("Hora del gasto");
        JPanel fh = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
        fh.setOpaque(false);
        fh.add(fecha);
        fh.add(hora);
        agregar(t, "FECHA GASTO", fh);

        JComboBox<Object> cat = new JComboBox<>();
        JComboBox<Object> prov = new JComboBox<>();
        JComboBox<Object> met = new JComboBox<>();
        Tema.estiloCombo(cat, PanelGastos::nombre);
        Tema.estiloCombo(prov, PanelGastos::nombre);
        Tema.estiloCombo(met, PanelGastos::nombre);
        cat.getAccessibleContext().setAccessibleName("Categoría");
        prov.getAccessibleContext().setAccessibleName("Proveedor");
        met.getAccessibleContext().setAccessibleName("Método de pago");
        try {
            llenarCategorias(app, cat, 0);
            llenarProveedores(app, prov, 0);
            llenarMetodos(app, met, null);
        } catch (java.sql.SQLException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudieron leer las listas", e.getMessage(), null);
            return;
        }
        agregar(t, "Categoría", conMas(cat, "Crear otra categoría", () -> nuevaCategoria(app, this, cat)));
        agregar(t, "Proveedor", conMas(prov, "Crear un proveedor", () -> nuevoProveedor(app, this, prov)));

        JComboBox<String> tipo = new JComboBox<>(new String[]{"CONTADO", "CRÉDITO"});
        Tema.estiloCombo(tipo, String::valueOf);
        tipo.getAccessibleContext().setAccessibleName("Tipo de gasto");
        JPanel tm = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
        tm.setOpaque(false);
        JPanel ct = (JPanel) apilados("Tipo de gasto", tipo, null, null);
        JPanel cm = (JPanel) apilados("Método de pago *", met, null, null);
        tm.add(ct);
        tm.add(cm);
        tm.setAlignmentX(LEFT_ALIGNMENT);
        Tema.anchoCompleto(tm);
        t.add(tm);
        t.add(Tema.espacio(6));
        JCheckBox caja = Tema.casilla("Sale de la caja (se pagó con plata del cajón)");
        caja.setSelected(true);
        caja.setAlignmentX(LEFT_ALIGNMENT);
        t.add(caja);
        JPanel notaCredito = new JPanel(new BorderLayout(10, 0));
        notaCredito.setOpaque(false);
        Controles.CampoFecha venceCredito = new Controles.CampoFecha("Paga hasta");
        venceCredito.setFecha(LocalDate.now().plusDays(rutas.logica.BaseCartera.PLAZO));
        notaCredito.add(apilados("Paga hasta", venceCredito, null, null), BorderLayout.WEST);
        JLabel txtCredito = new JLabel("<html>Queda en Cuentas por pagar; se abona desde el detalle o desde allá.</html>");
        txtCredito.setFont(Tema.texto(12.5f));
        txtCredito.setForeground(Tema.GRIS_TEXTO);
        notaCredito.add(txtCredito, BorderLayout.CENTER);
        notaCredito.setAlignmentX(LEFT_ALIGNMENT);
        Tema.anchoCompleto(notaCredito);
        t.add(notaCredito);
        t.add(Tema.espacio(10));
        Runnable ajustar = () -> {
            boolean credito = "CRÉDITO".equals(tipo.getSelectedItem());
            met.setEnabled(!credito);
            caja.setVisible(!credito && met.getSelectedItem() == MetodoPago.EFECTIVO);
            notaCredito.setVisible(credito);
            t.revalidate();
        };
        tipo.addActionListener(e -> ajustar.run());
        met.addActionListener(e -> ajustar.run());
        ajustar.run();

        Controles.AreaTexto desc = new Controles.AreaTexto("Ej. Arriendo del local de noviembre", 3);
        desc.setPreferredSize(new java.awt.Dimension(400, 70));
        agregar(t, "Descripción", desc);
        JTextField valor = Controles.campoNumero("$ 0");
        valor.getAccessibleContext().setAccessibleName("Valor del gasto");
        agregar(t, "Valor", valor);
        JLabel error = errorLabel();
        t.add(error);
        t.add(Tema.espacio(6));
        Tema.Boton si = Tema.botonPrimario("Agregar");
        Tema.Boton no = Tema.botonSecundario("Cancelar");
        JPanel f = Tema.fila(8, no, si);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        Capa capa = new Capa(this, t, true);
        Runnable hacer = () -> {
            try {
                BaseGastos.Gasto g = new BaseGastos.Gasto();
                LocalDate d = fecha.getFecha();
                if (d == null) {
                    throw new IllegalArgumentException("Escriba la fecha del gasto (dd/mm/aaaa)");
                }
                g.fecha = d.atTime((LocalTime) hora.getSelectedItem());
                if (!(cat.getSelectedItem() instanceof BaseGastos.Categoria)) {
                    throw new IllegalArgumentException("La categoría es requerida");
                }
                g.categoriaId = ((BaseGastos.Categoria) cat.getSelectedItem()).id;
                if (prov.getSelectedItem() instanceof Proveedor) {
                    g.proveedorId = ((Proveedor) prov.getSelectedItem()).id;
                }
                g.credito = "CRÉDITO".equals(tipo.getSelectedItem());
                g.metodo = met.getSelectedItem() instanceof MetodoPago ? (MetodoPago) met.getSelectedItem() : null;
                g.saleCaja = caja.isSelected();
                g.descripcion = desc.getText();
                g.valor = pesos(valor.getText());
                g.usuarioId = app.getSesion().getUsuario().getId();
                if (g.credito) {
                    g.vence = venceCredito.getFecha();
                    if (g.vence == null) {
                        throw new IllegalArgumentException("Escriba hasta cuándo hay que pagarlo (dd/mm/aaaa)");
                    }
                }
                app.getGastos().registrar(g);
                capa.cerrar();
                recargar();
                String pres = avisoPresupuesto(g.categoriaId, java.time.YearMonth.from(g.fecha));
                Aviso.mostrar(this, pres != null ? Aviso.Tipo.AVISO : Aviso.Tipo.BIEN, "Gasto No. " + g.numero + " guardado",
                        pres != null ? pres : Tarifa.pesos(g.valor)
                        + (g.credito ? " a crédito (por pagar)" : g.saleCaja ? " · salió de la caja" : ""));
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
        capa.mostrar(cat);
    }

    // ------------------------------------------------------------------
    //  Detalle, abonar y anular
    // ------------------------------------------------------------------

    private void verDetalle(BaseGastos.Gasto g0) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        BaseGastos.Gasto g;
        List<BaseGastos.Abono> abonos;
        try {
            g = app.getGastos().gasto(g0.id);
            abonos = app.getGastos().abonosDe(g.id);
        } catch (Exception e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo abrir el gasto", e.getMessage(), null);
            return;
        }
        JPanel t = Capa.tarjeta(520, 22, 30);
        t.add(Tema.tituloPantalla("Gasto No. " + g.numero));
        t.add(Tema.espacio(10));
        dato(t, "Fecha", PanelHistorialCompras.textoFecha(g.fecha));
        dato(t, "Categoría", g.categoria);
        dato(t, "Proveedor", g.proveedor.isEmpty() ? "Sin proveedor" : g.proveedor);
        dato(t, "Descripción", g.descripcion.isEmpty() ? "—" : g.descripcion);
        dato(t, "Responsable", g.responsable);
        dato(t, "Tipo", g.credito ? "Crédito" : "Contado · " + g.metodo + (g.saleCaja ? " · salió de la caja" : ""));
        dato(t, "Valor", Tarifa.pesos(g.valor));
        if (g.credito) {
            dato(t, "Abonado", Tarifa.pesos(g.abonado));
            dato(t, "Saldo", Tarifa.pesos(g.saldo()));
        }
        dato(t, "Estado", g.estado() + (g.anulado ? " · " + g.motivo : ""));
        if (!abonos.isEmpty()) {
            t.add(Tema.espacio(8));
            t.add(etiqueta("Abonos"));
            t.add(Tema.espacio(4));
            for (BaseGastos.Abono a : abonos) {
                JLabel l = new JLabel(PanelHistorialCompras.textoFecha(a.fecha) + "  ·  " + Tarifa.pesos(a.valor) + "  ·  "
                        + a.metodo + (a.saleCaja ? " (caja)" : "") + "  ·  " + a.responsable);
                l.setFont(Tema.texto(13));
                l.setForeground(Tema.GRIS_OSCURO);
                l.setAlignmentX(LEFT_ALIGNMENT);
                t.add(l);
            }
        }
        t.add(Tema.espacio(12));
        JLabel error = errorLabel();
        JPanel zonaAbono = new JPanel();
        zonaAbono.setOpaque(false);
        zonaAbono.setLayout(new javax.swing.BoxLayout(zonaAbono, javax.swing.BoxLayout.Y_AXIS));
        zonaAbono.setAlignmentX(LEFT_ALIGNMENT);
        JTextField valor = Controles.campoNumero("$ 0");
        JComboBox<Object> met = new JComboBox<>();
        JCheckBox caja = Tema.casilla("Sale de la caja");
        boolean abonable = !g.anulado && g.saldo() > 0;
        if (abonable) {
            Tema.estiloCombo(met, PanelGastos::nombre);
            met.getAccessibleContext().setAccessibleName("Método de pago del abono");
            try {
                llenarMetodos(app, met, null);
            } catch (java.sql.SQLException e) {
                Ubicaciones.registrarError(e);
            }
            valor.setText(String.valueOf(g.saldo()));
            valor.getAccessibleContext().setAccessibleName("Valor del abono");
            JPanel fila = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
            fila.setOpaque(false);
            fila.add(apilados("Valor del abono", valor, null, null));
            fila.add(apilados("Método de pago", met, null, null));
            fila.setAlignmentX(LEFT_ALIGNMENT);
            Tema.anchoCompleto(fila);
            zonaAbono.add(fila);
            zonaAbono.add(Tema.espacio(6));
            caja.setSelected(true);
            caja.setAlignmentX(LEFT_ALIGNMENT);
            zonaAbono.add(caja);
            met.addActionListener(e -> caja.setVisible(met.getSelectedItem() == MetodoPago.EFECTIVO));
            caja.setVisible(false);
            t.add(zonaAbono);
            t.add(Tema.espacio(4));
        }
        t.add(error);
        t.add(Tema.espacio(6));
        Tema.Boton cerrar = Tema.botonSecundario("Cerrar");
        List<JComponent> bs = new ArrayList<>();
        bs.add(cerrar);
        Capa capa = new Capa(this, t, true);
        if (!g.anulado) {
            Tema.Boton an = new Tema.Boton("Anular", Tema.BLANCO, Tema.ERROR_TEXTO, Tema.ERROR);
            an.addActionListener(e -> {
                capa.cerrar();
                anular(g);
            });
            bs.add(an);
        }
        if (abonable) {
            Tema.Boton ab = Tema.botonPrimario("Abonar");
            ab.addActionListener(e -> {
                try {
                    if (!(met.getSelectedItem() instanceof MetodoPago)) {
                        throw new IllegalArgumentException("El método de pago es requerido");
                    }
                    BaseGastos.Abono a = app.getGastos().abonar(g.id, pesos(valor.getText()),
                            (MetodoPago) met.getSelectedItem(), caja.isSelected(), app.getSesion().getUsuario().getId());
                    capa.cerrar();
                    recargar();
                    long queda = g.saldo() - a.valor;
                    Aviso.mostrar(this, Aviso.Tipo.BIEN, "Abono de " + Tarifa.pesos(a.valor) + " guardado",
                            queda == 0 ? "El gasto No. " + g.numero + " quedó PAGADO" : "Queda debiendo " + Tarifa.pesos(queda));
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    error.setText(ex.getMessage());
                } catch (java.sql.SQLException ex) {
                    Ubicaciones.registrarError(ex);
                    error.setText("No se pudo guardar el abono: " + ex.getMessage());
                }
            });
            bs.add(ab);
        }
        JPanel f = Tema.fila(8, bs.toArray(new JComponent[0]));
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        cerrar.addActionListener(e -> capa.cerrar());
        capa.mostrar(abonable ? valor : cerrar);
    }

    static void dato(JPanel t, String nombre, String valor) {
        JLabel l = new JLabel("<html><b>" + nombre + ":</b> " + escapar(valor) + "</html>");
        l.setFont(Tema.texto(13.5f));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setAlignmentX(LEFT_ALIGNMENT);
        t.add(l);
        t.add(Tema.espacio(3));
    }

    static String escapar(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Anular con motivo: no se borra, deja de contar (sus abonos tambien). */
    private void anular(BaseGastos.Gasto g) {
        if (g.anulado || Capa.hayAbierta(this)) {
            return;
        }
        JPanel t = Capa.tarjeta(480, 26, 30);
        t.add(Tema.tituloPantalla("¿Anular el gasto No. " + g.numero + "?"));
        t.add(Tema.espacio(8));
        Tema.Parrafo p = Tema.parrafo("Queda marcado como ANULADO y deja de sumar en los totales y en el informe"
                + (g.abonado > 0 ? " (sus abonos también)" : "") + ". No se borra: sigue saliendo en el historial.", 420);
        p.setFont(Tema.texto(13.5f));
        p.setForeground(Tema.GRIS_OSCURO);
        p.setAlignmentX(LEFT_ALIGNMENT);
        t.add(p);
        t.add(Tema.espacio(14));
        t.add(etiqueta("¿Por qué se anula?"));
        t.add(Tema.espacio(5));
        JTextField motivo = Tema.campo("Ej. Se registró dos veces");
        motivo.getAccessibleContext().setAccessibleName("Motivo de la anulación");
        motivo.setAlignmentX(LEFT_ALIGNMENT);
        Tema.anchoCompleto(motivo);
        t.add(motivo);
        JLabel error = errorLabel();
        t.add(Tema.espacio(4));
        t.add(error);
        t.add(Tema.espacio(10));
        Tema.Boton si = new Tema.Boton("Sí, anular gasto", Tema.BLANCO, Tema.ERROR_TEXTO, Tema.ERROR);
        Tema.Boton no = Tema.botonSecundario("Volver");
        JPanel f = Tema.fila(8, no, si);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        Capa capa = new Capa(this, t, true);
        Runnable hacer = () -> {
            try {
                app.getGastos().anular(g.id, app.getSesion().getUsuario().getId(), motivo.getText());
                capa.cerrar();
                recargar();
                Aviso.mostrar(this, Aviso.Tipo.BIEN, "Gasto No. " + g.numero + " anulado", "Ya no suma en los totales");
            } catch (IllegalArgumentException | IllegalStateException ex) {
                error.setText(ex.getMessage());
            } catch (java.sql.SQLException ex) {
                Ubicaciones.registrarError(ex);
                error.setText("No se pudo anular: " + ex.getMessage());
            }
        };
        si.addActionListener(e -> hacer.run());
        motivo.addActionListener(e -> hacer.run());
        no.addActionListener(e -> capa.cerrar());
        capa.mostrar(motivo);
    }

    /** Si con este gasto la categoría llega al 80 % o se pasa del presupuesto del mes: el texto del aviso. */
    private String avisoPresupuesto(long categoriaId, java.time.YearMonth mes) {
        try {
            rutas.logica.BaseCartera.Presupuesto p = app.getCartera().deCategoria(categoriaId, mes);
            if (p == null || p.usado() < 80) {
                return null;
            }
            return "Con este gasto, " + p.categoria + " llega al " + Math.round(p.usado()) + " % del presupuesto ("
                    + Tarifa.pesos(p.gastado) + " de " + Tarifa.pesos(p.valor) + ")";
        } catch (java.sql.SQLException e) {
            Ubicaciones.registrarError(e);
            return null;
        }
    }

    static LocalDateTime hoy(boolean fin) {
        return fin ? LocalDate.now().atTime(23, 59) : LocalDate.now().atStartOfDay();
    }
}
