package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import rutas.logica.BaseCartera;
import rutas.logica.BaseCartera.Documento;
import rutas.logica.BaseCartera.Rango;
import rutas.logica.BaseCartera.Tipo;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.modelo.MetodoPago;

/**
 * Cuentas por cobrar (lo fiado a los clientes) y Cuentas por pagar (compras y gastos a crédito):
 * fecha de corte, los seis cuadros de edades, la tabla del que lleva más días vencido al que menos
 * (montículo) y la ventana para abonar o anular un abono. Por cobrar se puede recordar por WhatsApp.
 */
public class PanelCartera extends ListaGastos<Documento> {

    private static final String[] COL_COBRAR = {"Item", "Identificación", "Cliente", "Venta", "Fecha", "Vence",
        "Días vencido", "Rango", "Valor", "Abonado", "Saldo", "Acción"};
    private static final String[] COL_PAGAR = {"Item", "Identificación", "Proveedor", "Tipo", "Documento", "Vence",
        "Días vencido", "Rango", "Valor", "Abonado", "Saldo", "Acción"};
    private static final int C_FECHA = 4, C_VENCE = 5, C_DIAS = 6, C_RANGO = 7, C_VALOR = 8, C_ABONADO = 9, C_SALDO = 10,
            C_ACCION = 11;
    static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final boolean cobrar;
    private final Controles.CampoFecha corte = new Controles.CampoFecha("Fecha de corte");
    private final JTextField buscar;
    private final Map<Rango, JLabel> cifras = new EnumMap<>(Rango.class);
    private final JLabel total = numero();

    /** cobrar: true = Cuentas por cobrar; false = Cuentas por pagar. */
    public PanelCartera(Aplicacion app, boolean cobrar) {
        super(app, cobrar ? "Cuentas por cobrar" : "Cuentas por pagar", cobrar ? COL_COBRAR : COL_PAGAR,
                new int[]{44, 112, 170, cobrar ? 70 : 120, 96, 96, 80, 96, 96, 90, 96, cobrar ? 170 : 96},
                cobrar ? "Nadie le debe a la tienda" : "La tienda no le debe a nadie",
                cobrar ? "Las ventas fiadas salen aquí hasta que el cliente las paga."
                        : "Las compras y los gastos a crédito salen aquí hasta que se pagan.", "notas");
        this.cobrar = cobrar;
        this.buscar = Tema.campo(cobrar ? "Buscar por cliente, cédula o número de venta" : "Buscar por proveedor o NIT");
        armar();
        consultar();
    }

    @Override
    protected JComponent filtros() {
        corte.setFecha(LocalDate.now());
        JPanel f = new JPanel(new GridBagLayout());
        f.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.SOUTHWEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 0, 14);
        g.weightx = 0;
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.add(etiqueta("Fecha de corte"));
        col.add(Box.createVerticalStrut(5));
        corte.setAlignmentX(LEFT_ALIGNMENT);
        corte.setPreferredSize(new java.awt.Dimension(200, corte.getPreferredSize().height));
        col.add(corte);
        f.add(col, g);
        Tema.Boton consultar = Tema.botonPrimario("Consultar");
        consultar.setIcon(Iconos.de("lupa", 16, Tema.BLANCO));
        consultar.setToolTipText("Ver lo que se debía ese día (Enter)");
        consultar.addActionListener(e -> consultar());
        f.add(consultar, g);
        g.weightx = 1;
        f.add(Box.createHorizontalGlue(), g);
        g.weightx = 0;
        g.insets = new Insets(0, 0, 0, 0);
        buscar.setPreferredSize(new java.awt.Dimension(340, 38));
        buscar.getAccessibleContext().setAccessibleName(cobrar ? "Buscar cliente" : "Buscar proveedor");
        buscar.getDocument().addDocumentListener(PanelProductos.alCambiar(this::consultar));
        f.add(buscar, g);

        JPanel cuadros = new JPanel(new GridLayout(1, 6, 10, 0));
        cuadros.setOpaque(false);
        Rango[] orden = {Rango.D1_30, Rango.D31_60, Rango.D61_90, Rango.MAS_90, Rango.POR_VENCER};
        for (Rango r : orden) {
            JLabel n = numero();
            n.setFont(Tema.titulo(19));
            cifras.put(r, n);
            cuadros.add(cuadro(r == Rango.POR_VENCER ? "Por vencer" : r == Rango.MAS_90 ? "Más de 90 días"
                    : r.texto, n, null));
        }
        total.setFont(Tema.titulo(19));
        cuadros.add(cuadro(cobrar ? "Total por cobrar" : "Total por pagar", total, null));
        JPanel todo = new JPanel(new BorderLayout(0, 14));
        todo.setOpaque(false);
        todo.add(f, BorderLayout.NORTH);
        todo.add(cuadros, BorderLayout.CENTER);
        return todo;
    }

    @Override
    protected List<Documento> leer() throws Exception {
        LocalDate d = corte.getFecha();
        if (d == null) {
            throw new IllegalArgumentException("Escriba la fecha de corte (dd/mm/aaaa)");
        }
        List<Documento> l = cobrar ? app.getCartera().cobrar(d, buscar.getText()) : app.getCartera().pagar(d, buscar.getText());
        BaseCartera.Edades e = BaseCartera.edades(l);
        for (Map.Entry<Rango, JLabel> x : cifras.entrySet()) {
            long v = e.porRango.get(x.getKey());
            x.getValue().setText(Tarifa.pesos(v));
            x.getValue().setForeground(v > 0 && x.getKey() == Rango.MAS_90 ? Tema.ERROR_TEXTO : Tema.AZUL_PROFUNDO);
        }
        total.setText(Tarifa.pesos(e.total));
        return l;
    }

    @Override
    protected Object valor(Documento d, int c) {
        switch (c) {
            case 1:
                return d.identificacion.isEmpty() ? "—" : d.identificacion;
            case 2:
                return d.nombre;
            case 3:
                return cobrar ? "No. " + d.numero : d.tipo.texto;
            case C_FECHA:
                return cobrar ? d.fecha.format(DIA) : (d.tipo == Tipo.COMPRA ? "Compra " : "Gasto ") + d.numero;
            case C_VENCE:
                return d.vence.format(DIA);
            case C_DIAS:
                return d.diasVencido > 0 ? String.valueOf(d.diasVencido) : "—";
            case C_RANGO:
                return d.rango().texto;
            case C_VALOR:
                return Tarifa.pesos(d.valor);
            case C_ABONADO:
                return Tarifa.pesos(d.abonado);
            case C_SALDO:
                return Tarifa.pesos(d.saldo());
            default:
                return "";
        }
    }

    @Override
    protected List<Accion> acciones(Documento d, int c) {
        if (c != C_ACCION) {
            return List.of();
        }
        List<Accion> l = new ArrayList<>();
        l.add(new Accion(null, "Abonar", Tema.AZUL, "Registrar un abono y ver los anteriores", () -> abonar(d)));
        if (cobrar) {
            l.add(new Accion(null, "WhatsApp", Tema.AZUL, d.celular.isEmpty() ? "El cliente no tiene celular guardado"
                    : "Recordarle al cliente por WhatsApp (el mensaje va escrito)", () -> recordar(d)));
        }
        return l;
    }

    @Override
    protected Color color(Documento d, int c) {
        if (c == C_RANGO || c == C_DIAS || c == C_VENCE) {
            Rango r = d.rango();
            return r == Rango.POR_VENCER ? Tema.EXITO_TEXTO : r == Rango.MAS_90 || r == Rango.D61_90 ? Tema.ERROR_TEXTO
                    : Tema.AVISO_TEXTO;
        }
        if (c == 3 && !cobrar) {
            return d.tipo == Tipo.COMPRA ? Tema.AZUL : Tema.AVISO_TEXTO;
        }
        return c == 1 && d.identificacion.isEmpty() || c == C_ABONADO ? Tema.GRIS_TEXTO : null;
    }

    @Override
    protected boolean negrita(Documento d, int c) {
        return c == C_SALDO || c == C_RANGO || (c == 3 && !cobrar);
    }

    @Override
    protected int alineacion(int c) {
        return c == 2 ? SwingConstants.LEFT : c == C_VALOR || c == C_ABONADO || c == C_SALDO ? SwingConstants.RIGHT
                : SwingConstants.CENTER;
    }

    @Override
    protected int minimo(int c, int ancho) {
        return c == C_ACCION || c == C_SALDO || c == C_VALOR || c == 1 ? ancho : c == C_RANGO || c == C_VENCE || (cobrar && c == C_FECHA) ? 90 : Math.min(ancho, 64);
    }

    @Override
    protected String resumen(List<Documento> l) {
        java.util.Set<String> nombres = new java.util.HashSet<>();
        for (Documento d : l) {
            nombres.add(rutas.modelo.Geo.normalizar(d.nombre));
        }
        return l.size() + (l.size() == 1 ? " documento" : " documentos") + " de " + nombres.size()
                + (cobrar ? (nombres.size() == 1 ? " cliente" : " clientes") : (nombres.size() == 1 ? " proveedor" : " proveedores"));
    }

    @Override
    protected void abrir(Documento d) {
        abonar(d);
    }

    @Override
    protected String subtituloPdf() {
        LocalDate d = corte.fechaSiEsValida();
        return d == null ? "" : "corte " + d.format(DIA);
    }

    // ------------------------------------------------------------------
    //  Recordar por WhatsApp
    // ------------------------------------------------------------------

    /** El mensaje para el cliente: cuánto debe en total y desde cuándo (sin regaños). */
    static String mensajeCobro(String negocio, String cliente, long saldo, int ventas, LocalDate vence, long diasVencido) {
        String n = negocio == null || negocio.isBlank() ? "la tienda" : negocio.trim();
        String primero = cliente.trim().split("\\s+")[0];
        String nombre = primero.isEmpty() ? "" : " " + primero.substring(0, 1).toUpperCase() + primero.substring(1).toLowerCase();
        return "Hola" + nombre + ", te saludamos de " + n + ". Te recordamos que tienes un saldo pendiente de "
                + Tarifa.pesos(saldo) + (ventas > 1 ? " (" + ventas + " compras)" : "")
                + (diasVencido > 0 ? ", que venció el " + vence.format(DIA) : ", con fecha de pago " + vence.format(DIA))
                + ". Puedes abonar en la tienda o por Nequi/transferencia. ¡Gracias por tu confianza!";
    }

    private void recordar(Documento d) {
        String cel = rutas.logica.EnlacesQR.celular(d.celular);
        if (cel.isEmpty()) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "El cliente no tiene celular",
                    "Guarde su celular en Clientes para poder escribirle por WhatsApp.", null);
            return;
        }
        long[] t = BaseCartera.totalDe(filas, d.nombre);
        String negocio = app.getNegocio();
        try {
            AbrirWhatsApp.abrir(cel, mensajeCobro(negocio, d.nombre, t[0], (int) t[1], d.vence, d.diasVencido));
        } catch (Exception e) {
            Ubicaciones.registrarError(e);
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo abrir WhatsApp", e.getMessage(), null);
        }
    }

    // ------------------------------------------------------------------
    //  Registrar abono (como PuveSoft: formulario a la izquierda, lista a la derecha)
    // ------------------------------------------------------------------

    private void abonar(Documento d0) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        Documento d;
        List<BaseCartera.Abono> abonos;
        try {
            d = app.getCartera().documento(d0.tipo, d0.id);
            abonos = app.getCartera().abonos(d.tipo, d.id);
        } catch (Exception e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo abrir", e.getMessage(), null);
            recargar();
            return;
        }
        JPanel t = Capa.tarjeta(900, 22, 28);
        String que = d.tipo == Tipo.FIADO ? "venta No. " + d.numero : d.tipo == Tipo.COMPRA ? "compra No. " + d.numero
                : "gasto No. " + d.numero;
        t.add(Tema.tituloPantalla("Registrar abono · " + que));
        t.add(Tema.espacio(4));
        long[] tot = BaseCartera.totalDe(filas, d.nombre);
        JLabel quien = new JLabel((cobrar ? "Cliente: " : "Proveedor: ") + d.nombre
                + (tot[1] > 1 ? "   ·   Debe en total " + Tarifa.pesos(tot[0]) + " en " + tot[1] + " documentos" : ""));
        quien.setFont(Tema.textoFuerte(13.5f));
        quien.setForeground(Tema.AZUL_PROFUNDO);
        quien.setAlignmentX(LEFT_ALIGNMENT);
        t.add(quien);
        t.add(Tema.espacio(12));

        // izquierda: el formulario
        JPanel izq = new JPanel();
        izq.setOpaque(false);
        izq.setLayout(new BoxLayout(izq, BoxLayout.Y_AXIS));
        Controles.CampoFecha fecha = new Controles.CampoFecha("Fecha de pago");
        fecha.setFecha(LocalDate.now());
        JComboBox<Object> met = new JComboBox<>();
        Tema.estiloCombo(met, PanelGastos::nombre);
        met.getAccessibleContext().setAccessibleName("Método de pago");
        try {
            PanelGastos.llenarMetodos(app, met, MetodoPago.EFECTIVO);
        } catch (java.sql.SQLException e) {
            Ubicaciones.registrarError(e);
        }
        JPanel fm = new JPanel(new GridLayout(1, 2, 8, 0));
        fm.setOpaque(false);
        fm.add(apilados("Fecha de pago", fecha, null, null));
        fm.add(apilados("Método de pago", met, null, null));
        fm.setAlignmentX(LEFT_ALIGNMENT);
        Tema.anchoCompleto(fm);
        izq.add(fm);
        izq.add(Tema.espacio(10));
        JTextField valor = Controles.campoNumero("$ 0");
        valor.setText(String.valueOf(d.saldo()));
        valor.getAccessibleContext().setAccessibleName("Valor del abono");
        PanelGastos.agregar(izq, "Valor a pagar (saldo " + Tarifa.pesos(d.saldo()) + ")", valor);
        JCheckBox caja = Tema.casilla(cobrar ? "Entra a la caja (efectivo)" : "Sale de la caja (efectivo)");
        caja.setSelected(true);
        caja.setAlignmentX(LEFT_ALIGNMENT);
        caja.setVisible(!cobrar && met.getSelectedItem() == MetodoPago.EFECTIVO); // por cobrar siempre entra a la caja
        izq.add(caja);
        met.addActionListener(e -> caja.setVisible(!cobrar && met.getSelectedItem() == MetodoPago.EFECTIVO));
        JLabel error = PanelGastos.errorLabel();
        izq.add(error);
        izq.add(Tema.espacio(6));
        Tema.Boton ok = Tema.botonAccion("Abonar");
        Tema.anchoCompleto(ok);
        ok.setAlignmentX(LEFT_ALIGNMENT);
        izq.add(ok);

        // derecha: lo que vale, la lista de abonos y lo pendiente
        JPanel der = new JPanel();
        der.setOpaque(false);
        der.setLayout(new BoxLayout(der, BoxLayout.Y_AXIS));
        JLabel valorCredito = new JLabel("Valor " + (cobrar ? "fiado" : "a crédito") + ": " + Tarifa.pesos(d.valor)
                + "   ·   vence " + d.vence.format(DIA));
        valorCredito.setFont(Tema.textoFuerte(14));
        valorCredito.setForeground(Tema.GRIS_OSCURO);
        valorCredito.setAlignmentX(LEFT_ALIGNMENT);
        der.add(valorCredito);
        der.add(Tema.espacio(8));
        der.add(etiqueta("Lista de abonos"));
        der.add(Tema.espacio(4));
        long abonado = 0;
        if (abonos.isEmpty()) {
            JLabel v = new JLabel("Todavía no tiene abonos.");
            v.setFont(Tema.texto(13));
            v.setForeground(Tema.GRIS_TEXTO);
            v.setAlignmentX(LEFT_ALIGNMENT);
            der.add(v);
        }
        Capa[] capa = new Capa[1];
        for (BaseCartera.Abono a : abonos) {
            if (!a.anulado) {
                abonado += a.valor;
            }
            JPanel fila = new JPanel(new BorderLayout(8, 0));
            fila.setOpaque(false);
            fila.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Tema.BORDE));
            fila.setAlignmentX(LEFT_ALIGNMENT);
            String txt = "<html><div style='width:175px'>" + PanelHistorialCompras.textoFecha(a.fecha) + "  ·  " + a.metodo
                    + (a.saleCaja ? " (caja)" : "") + "  ·  " + PanelGastos.escapar(a.responsable)
                    + (a.anulado ? "<br><span style='color:#B03030'>Anulado: " + PanelGastos.escapar(a.motivo) + "</span>" : "")
                    + "</div></html>";
            JLabel l = new JLabel(txt);
            l.setFont(Tema.texto(12.5f));
            l.setForeground(a.anulado ? Tema.GRIS_TEXTO : Tema.GRIS_OSCURO);
            l.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
            fila.add(l, BorderLayout.CENTER);
            JPanel derFila = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 4));
            derFila.setOpaque(false);
            JLabel v = new JLabel(Tarifa.pesos(a.valor));
            v.setFont(Tema.textoFuerte(13));
            v.setForeground(a.anulado ? Tema.GRIS_TEXTO : Tema.GRIS_OSCURO);
            derFila.add(v);
            if (!a.anulado) {
                Tema.Boton an = new Tema.Boton("Anular", Tema.BLANCO, Tema.ERROR_TEXTO, Tema.ERROR);
                an.setFont(Tema.textoFuerte(12));
                an.setToolTipText("Anular este abono (pide el motivo)");
                an.addActionListener(e -> {
                    capa[0].cerrar();
                    anularAbono(d, a);
                });
                derFila.add(an);
            }
            fila.add(derFila, BorderLayout.EAST);
            fila.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, fila.getPreferredSize().height));
            der.add(fila);
        }
        der.add(Tema.espacio(8));
        JLabel tAb = new JLabel("Total abonado: " + Tarifa.pesos(abonado) + "     Total pendiente: " + Tarifa.pesos(d.saldo()));
        tAb.setFont(Tema.textoFuerte(14));
        tAb.setForeground(Tema.AZUL_PROFUNDO);
        tAb.setAlignmentX(LEFT_ALIGNMENT);
        der.add(tAb);
        der.add(Box.createVerticalGlue());

        JPanel dos = new JPanel(new GridLayout(1, 2, 24, 0));
        dos.setOpaque(false);
        dos.add(izq);
        javax.swing.JScrollPane sc = new javax.swing.JScrollPane(der);
        sc.setBorder(null);
        sc.setOpaque(false);
        sc.getViewport().setOpaque(false);
        sc.setPreferredSize(new java.awt.Dimension(420, 260));
        sc.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sc.getVerticalScrollBar().setUnitIncrement(14);
        dos.add(sc);
        dos.setAlignmentX(LEFT_ALIGNMENT);
        t.add(dos);
        t.add(Tema.espacio(12));
        Tema.Boton cerrar = Tema.botonSecundario("Cerrar");
        JPanel f = Tema.fila(8, cerrar);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        capa[0] = new Capa(this, t, true);
        Runnable hacer = () -> {
            try {
                if (!(met.getSelectedItem() instanceof MetodoPago)) {
                    throw new IllegalArgumentException("Escoja el método de pago");
                }
                LocalDate fd = fecha.getFecha();
                if (fd == null) {
                    throw new IllegalArgumentException("Escriba la fecha de pago (dd/mm/aaaa)");
                }
                long v = PanelGastos.pesos(valor.getText());
                MetodoPago m = (MetodoPago) met.getSelectedItem();
                long usuario = app.getSesion().getUsuario().getId();
                if (d.tipo == Tipo.GASTO) {
                    if (!fd.equals(LocalDate.now())) {
                        throw new IllegalArgumentException("Los abonos a gastos van con la fecha de hoy");
                    }
                    app.getGastos().abonar(d.id, v, m, caja.isSelected(), usuario);
                } else {
                    app.getCartera().abonar(d.tipo, d.id, fd, v, m, caja.isSelected(), usuario);
                }
                capa[0].cerrar();
                recargar();
                long queda = d.saldo() - v;
                Aviso.mostrar(this, Aviso.Tipo.BIEN, "Abono de " + Tarifa.pesos(v) + " guardado",
                        queda == 0 ? "Quedó pagado completo" : "Queda pendiente " + Tarifa.pesos(queda));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                error.setText(ex.getMessage());
            } catch (java.sql.SQLException ex) {
                Ubicaciones.registrarError(ex);
                error.setText("No se pudo guardar el abono: " + ex.getMessage());
            }
        };
        ok.addActionListener(e -> hacer.run());
        valor.addActionListener(e -> hacer.run());
        cerrar.addActionListener(e -> capa[0].cerrar());
        capa[0].mostrar(valor);
    }

    /** Anular un abono: pide el motivo; el abono queda en la lista marcado y el saldo vuelve a subir. */
    private void anularAbono(Documento d, BaseCartera.Abono a) {
        JPanel t = Capa.tarjeta(460, 26, 30);
        t.add(Tema.tituloPantalla("¿Anular el abono de " + Tarifa.pesos(a.valor) + "?"));
        t.add(Tema.espacio(8));
        Tema.Parrafo p = Tema.parrafo("El saldo vuelve a subir en " + Tarifa.pesos(a.valor) + ". El abono no se borra:"
                + " queda en la lista marcado como anulado, con el motivo.", 400);
        p.setFont(Tema.texto(13.5f));
        p.setForeground(Tema.GRIS_OSCURO);
        p.setAlignmentX(LEFT_ALIGNMENT);
        t.add(p);
        t.add(Tema.espacio(12));
        t.add(etiqueta("¿Por qué se anula?"));
        t.add(Tema.espacio(5));
        JTextField motivo = Tema.campo("Ej. Se registró dos veces");
        motivo.getAccessibleContext().setAccessibleName("Motivo de la anulación");
        motivo.setAlignmentX(LEFT_ALIGNMENT);
        Tema.anchoCompleto(motivo);
        t.add(motivo);
        JLabel error = PanelGastos.errorLabel();
        t.add(Tema.espacio(4));
        t.add(error);
        t.add(Tema.espacio(8));
        Tema.Boton si = new Tema.Boton("Sí, anular abono", Tema.BLANCO, Tema.ERROR_TEXTO, Tema.ERROR);
        Tema.Boton no = Tema.botonSecundario("Volver");
        JPanel f = Tema.fila(8, no, si);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        Capa capa = new Capa(this, t, true);
        Runnable hacer = () -> {
            try {
                app.getCartera().anularAbono(d.tipo, a.id, app.getSesion().getUsuario().getId(), motivo.getText());
                capa.cerrar();
                recargar();
                Aviso.mostrar(this, Aviso.Tipo.BIEN, "Abono anulado", "El saldo volvió a subir");
            } catch (IllegalArgumentException | IllegalStateException ex) {
                error.setText(ex.getMessage());
            } catch (java.sql.SQLException ex) {
                Ubicaciones.registrarError(ex);
                error.setText("No se pudo anular: " + ex.getMessage());
            }
        };
        si.addActionListener(e -> hacer.run());
        motivo.addActionListener(e -> hacer.run());
        no.addActionListener(e -> {
            capa.cerrar();
            abonar(d);
        });
        capa.mostrar(motivo);
    }
}
