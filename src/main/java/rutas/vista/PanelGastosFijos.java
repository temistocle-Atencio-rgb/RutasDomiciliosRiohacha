package rutas.vista;

import java.awt.Color;
import java.awt.FlowLayout;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import rutas.logica.BaseGastos;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.modelo.MetodoPago;
import rutas.modelo.Proveedor;

/**
 * Gastos › Gastos fijos: lo que se paga cada mes (arriendo, internet, nómina). Van del que se vence
 * primero al último (montículo de mínimos). «Pagar» crea el gasto de este mes y lo pasa al siguiente.
 */
public class PanelGastosFijos extends ListaGastos<BaseGastos.Fijo> {

    private static final String[] COLUMNAS = {"Item", "Descripción", "Categoría", "Proveedor", "Método de pago",
        "Fecha Aplicación", "Valor", "Pagar", "Sacar Caja", "Acción"};
    private static final int C_FECHA = 5, C_VALOR = 6, C_PAGAR = 7, C_CAJA = 8, C_ACCION = 9;
    private static final java.time.format.DateTimeFormatter DIA = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public PanelGastosFijos(Aplicacion app) {
        super(app, "Gastos Fijos", COLUMNAS, new int[]{50, 220, 150, 150, 130, 160, 110, 100, 100, 90},
                "No hay gastos fijos", "Agregue el arriendo, los servicios o lo que se paga cada mes.", "calendario");
        armar();
        consultar();
    }

    @Override
    protected JComponent filtros() {
        JPanel f = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        f.setOpaque(false);
        Tema.Boton nuevo = Tema.botonAccion("Nuevo Gasto Fijo");
        nuevo.addActionListener(e -> editar(null));
        Tema.Boton regresar = Tema.botonSecundario("Regresar");
        regresar.setToolTipText("Volver al historial de gastos");
        regresar.addActionListener(e -> VentanaPrincipal.irA(this, "Gastos"));
        f.add(nuevo);
        f.add(regresar);
        return f;
    }

    @Override
    protected List<BaseGastos.Fijo> leer() throws Exception {
        return app.getGastos().fijos();
    }

    @Override
    protected Object valor(BaseGastos.Fijo x, int c) {
        switch (c) {
            case 1:
                return x.descripcion.isEmpty() ? x.categoria : x.descripcion;
            case 2:
                return x.categoria;
            case 3:
                return x.proveedor.isEmpty() ? "Sin proveedor" : x.proveedor;
            case 4:
                return x.metodo.toString();
            case C_FECHA:
                return x.proximo.format(DIA) + "  ·  " + cuando(x.diasPara(LocalDate.now()));
            case C_VALOR:
                return Tarifa.pesos(x.valor);
            case C_CAJA:
                return x.metodo == MetodoPago.EFECTIVO ? "Sí" : "No";
            default:
                return "";
        }
    }

    static String cuando(long dias) {
        return dias < 0 ? "vencido hace " + (-dias) + (dias == -1 ? " día" : " días")
                : dias == 0 ? "hoy" : dias == 1 ? "mañana" : "en " + dias + " días";
    }

    @Override
    protected List<Accion> acciones(BaseGastos.Fijo x, int c) {
        if (c == C_PAGAR) {
            return List.of(new Accion(null, "Pagar", Tema.AZUL, "Registrar el pago de este mes", () -> pagar(x)));
        }
        if (c == C_ACCION) {
            return List.of(new Accion(PanelProductos.Icono.LAPIZ, null, Tema.AZUL, "Editar", () -> editar(x)),
                    new Accion(PanelProductos.Icono.PAPELERA, null, Tema.ERROR, "Quitar", () -> quitar(x)));
        }
        return List.of();
    }

    @Override
    protected Color color(BaseGastos.Fijo x, int c) {
        long d = x.diasPara(LocalDate.now());
        if (c == C_FECHA) {
            return d < 0 ? Tema.ERROR_TEXTO : d <= 3 ? Tema.AVISO_TEXTO : null;
        }
        return c == 3 && x.proveedor.isEmpty() ? Tema.GRIS_TEXTO : null;
    }

    @Override
    protected boolean negrita(BaseGastos.Fijo x, int c) {
        return c == C_VALOR || (c == C_FECHA && x.diasPara(LocalDate.now()) <= 3);
    }

    @Override
    protected int alineacion(int c) {
        return c == 1 || c == 3 ? SwingConstants.LEFT : c == C_VALOR ? SwingConstants.RIGHT : SwingConstants.CENTER;
    }

    @Override
    protected String resumen(List<BaseGastos.Fijo> l) {
        long total = 0;
        for (BaseGastos.Fijo x : l) {
            total += x.valor;
        }
        return "TOTAL GASTOS: " + Tarifa.pesos(total) + " al mes";
    }

    @Override
    protected void abrir(BaseGastos.Fijo x) {
        editar(x);
    }

    // ------------------------------------------------------------------

    private void editar(BaseGastos.Fijo x0) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        BaseGastos.Fijo x = x0 == null ? new BaseGastos.Fijo() : x0;
        JPanel t = Capa.tarjeta(500, 22, 30);
        t.add(Tema.tituloPantalla(x.id == 0 ? "Nuevo Gasto Fijo" : "Actualizar Gasto Fijo"));
        t.add(Tema.espacio(12));
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
            PanelGastos.llenarCategorias(app, cat, x.categoriaId);
            PanelGastos.llenarProveedores(app, prov, x.proveedorId);
            PanelGastos.llenarMetodos(app, met, x.id == 0 ? null : x.metodo);
        } catch (java.sql.SQLException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudieron leer las listas", e.getMessage(), null);
            return;
        }
        PanelGastos.agregar(t, "Categoría", PanelGastos.conMas(cat, "Crear otra categoría",
                () -> PanelGastos.nuevaCategoria(app, this, cat)));
        PanelGastos.agregar(t, "Proveedor", PanelGastos.conMas(prov, "Crear un proveedor",
                () -> PanelGastos.nuevoProveedor(app, this, prov)));
        PanelGastos.agregar(t, "Método de pago", met);
        JTextField desc = Tema.campo("Ej. Arriendo del local");
        desc.setText(x.descripcion);
        desc.getAccessibleContext().setAccessibleName("Descripción");
        PanelGastos.agregar(t, "Descripción", desc);
        JTextField valor = Controles.campoNumero("$ 0");
        valor.getAccessibleContext().setAccessibleName("Valor");
        if (x.valor > 0) {
            valor.setText(String.valueOf(x.valor));
        }
        PanelGastos.agregar(t, "Valor", valor);
        Controles.CampoFecha fecha = new Controles.CampoFecha("Fecha de aplicación");
        fecha.setFecha(x.proximo != null ? x.proximo : LocalDate.now());
        PanelGastos.agregar(t, "Fecha Aplicación (el próximo pago; luego se repite el mismo día cada mes)", fecha);
        JLabel error = PanelGastos.errorLabel();
        t.add(error);
        t.add(Tema.espacio(6));
        Tema.Boton si = Tema.botonPrimario(x.id == 0 ? "Agregar" : "Actualizar");
        Tema.Boton no = Tema.botonSecundario("Cancelar");
        JPanel f = Tema.fila(8, no, si);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        Capa capa = new Capa(this, t, true);
        Runnable hacer = () -> {
            try {
                if (!(cat.getSelectedItem() instanceof BaseGastos.Categoria)) {
                    throw new IllegalArgumentException("La categoría es requerida");
                }
                if (!(met.getSelectedItem() instanceof MetodoPago)) {
                    throw new IllegalArgumentException("El método de pago es requerido");
                }
                LocalDate d = fecha.getFecha();
                if (d == null) {
                    throw new IllegalArgumentException("Escriba la fecha de aplicación (dd/mm/aaaa)");
                }
                x.categoriaId = ((BaseGastos.Categoria) cat.getSelectedItem()).id;
                x.proveedorId = prov.getSelectedItem() instanceof Proveedor ? ((Proveedor) prov.getSelectedItem()).id : 0;
                x.metodo = (MetodoPago) met.getSelectedItem();
                x.descripcion = desc.getText();
                x.valor = PanelGastos.pesos(valor.getText());
                x.dia = d.getDayOfMonth();
                x.proximo = d;
                app.getGastos().guardarFijo(x);
                capa.cerrar();
                recargar();
                Aviso.mostrar(this, Aviso.Tipo.BIEN, "Gasto fijo guardado", "Se paga el día " + x.dia + " de cada mes");
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

    private void pagar(BaseGastos.Fijo x) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        JPanel t = Capa.tarjeta(460, 24, 30);
        t.add(Tema.tituloPantalla("¿Pagar " + (x.descripcion.isEmpty() ? x.categoria : x.descripcion) + "?"));
        t.add(Tema.espacio(8));
        Tema.Parrafo p = Tema.parrafo("Se registra un gasto de " + Tarifa.pesos(x.valor) + " con " + x.metodo
                + " y el próximo pago queda para el " + BaseGastos.fechaEnMes(java.time.YearMonth.from(x.proximo).plusMonths(1),
                x.dia).format(DIA) + ".", 400);
        p.setFont(Tema.texto(13.5f));
        p.setForeground(Tema.GRIS_OSCURO);
        p.setAlignmentX(LEFT_ALIGNMENT);
        t.add(p);
        t.add(Tema.espacio(8));
        JCheckBox caja = Tema.casilla("Sale de la caja (se paga con plata del cajón)");
        caja.setSelected(true);
        caja.setAlignmentX(LEFT_ALIGNMENT);
        caja.setVisible(x.metodo == MetodoPago.EFECTIVO);
        t.add(caja);
        JLabel error = PanelGastos.errorLabel();
        t.add(error);
        t.add(Tema.espacio(8));
        Tema.Boton si = Tema.botonPrimario("Sí, pagar");
        Tema.Boton no = Tema.botonSecundario("Volver");
        JPanel f = Tema.fila(8, no, si);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        Capa capa = new Capa(this, t, true);
        si.addActionListener(e -> {
            try {
                BaseGastos.Gasto g = app.getGastos().pagarFijo(x, caja.isSelected(), app.getSesion().getUsuario().getId());
                capa.cerrar();
                recargar();
                Aviso.mostrar(this, Aviso.Tipo.BIEN, "Gasto No. " + g.numero + " registrado",
                        "Próximo pago: " + x.proximo.format(DIA));
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
            } catch (java.sql.SQLException ex) {
                Ubicaciones.registrarError(ex);
                error.setText("No se pudo pagar: " + ex.getMessage());
            }
        });
        no.addActionListener(e -> capa.cerrar());
        capa.mostrar(si);
    }

    private void quitar(BaseGastos.Fijo x) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        Alerta.confirmar(this, "¿Quitar este gasto fijo?", "Deja de salir cada mes. Los gastos que ya se pagaron siguen en"
                + " el historial.", "Sí, quitar", "Volver", () -> {
                    try {
                        app.getGastos().quitarFijo(x.id);
                        recargar();
                    } catch (java.sql.SQLException ex) {
                        Ubicaciones.registrarError(ex);
                        Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo quitar", ex.getMessage(), null);
                    }
                });
    }

    /** Para la prueba sin ventana: los que se ven. */
    List<BaseGastos.Fijo> visibles() {
        List<BaseGastos.Fijo> l = new ArrayList<>();
        for (int i = 0; i < filasVisibles(); i++) {
            l.add(filaVisible(i));
        }
        return l;
    }
}
