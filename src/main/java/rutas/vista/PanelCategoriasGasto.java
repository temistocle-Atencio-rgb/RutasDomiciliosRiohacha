package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import rutas.logica.BaseGastos;
import rutas.logica.Ubicaciones;

/**
 * Gastos › Categorías de gastos: nombre, estado y (si se quiere) el concepto DIAN del formato 1001
 * y si es deducible de renta. Las que ya tienen gastos no se borran: se desactivan.
 */
public class PanelCategoriasGasto extends ListaGastos<BaseGastos.Categoria> {

    private static final String[] COLUMNAS = {"Item", "Nombre", "Concepto 1001", "Renta", "Estado", "Acción"};
    private final JTextField campoBuscar = Tema.campo("Buscar por nombre");

    public PanelCategoriasGasto(Aplicacion app) {
        super(app, "Categorías de Gastos", COLUMNAS, new int[]{50, 260, 300, 150, 110, 100},
                "No hay registros", "Cree una categoría con «Nueva Categoría».", "categorias");
        armar();
        consultar();
    }

    @Override
    protected JComponent filtros() {
        JPanel f = new JPanel(new BorderLayout(12, 0));
        f.setOpaque(false);
        campoBuscar.setPreferredSize(new java.awt.Dimension(340, 38));
        campoBuscar.getAccessibleContext().setAccessibleName("Buscar categoría por nombre");
        campoBuscar.getDocument().addDocumentListener(PanelProductos.alCambiar(this::consultar));
        f.add(campoBuscar, BorderLayout.WEST);
        Tema.Boton nueva = Tema.botonAccion("Nueva Categoría");
        nueva.addActionListener(e -> editar(null));
        f.add(nueva, BorderLayout.EAST);
        return f;
    }

    @Override
    protected List<BaseGastos.Categoria> leer() throws Exception {
        String b = rutas.modelo.Geo.normalizar(campoBuscar.getText());
        List<BaseGastos.Categoria> l = new ArrayList<>();
        for (BaseGastos.Categoria c : app.getGastos().categorias(false)) {
            if (b.isEmpty() || rutas.modelo.Geo.normalizar(c.nombre).contains(b)) {
                l.add(c);
            }
        }
        return l;
    }

    @Override
    protected Object valor(BaseGastos.Categoria c, int col) {
        switch (col) {
            case 1:
                return c.nombre;
            case 2:
                return c.textoConcepto();
            case 3:
                return c.renta.texto;
            case 4:
                return c.activa ? "Activo" : "Inactivo";
            default:
                return "";
        }
    }

    @Override
    protected List<Accion> acciones(BaseGastos.Categoria c, int col) {
        if (col != 5) {
            return List.of();
        }
        return List.of(new Accion(PanelProductos.Icono.LAPIZ, null, Tema.AZUL, "Editar", () -> editar(c)),
                c.activa ? new Accion(PanelProductos.Icono.PAPELERA, null, Tema.ERROR, "Quitar", () -> quitar(c))
                        : new Accion(PanelProductos.Icono.REACTIVAR, null, Tema.EXITO_TEXTO, "Activar otra vez",
                                () -> activar(c)));
    }

    @Override
    protected Color color(BaseGastos.Categoria c, int col) {
        if (col == 4) {
            return c.activa ? Tema.EXITO_TEXTO : Tema.ERROR_TEXTO;
        }
        if ((col == 2 && c.concepto.isEmpty()) || (col == 3 && c.renta == BaseGastos.Renta.SIN_CLASIFICAR) || !c.activa) {
            return Tema.GRIS_TEXTO;
        }
        return null;
    }

    @Override
    protected boolean negrita(BaseGastos.Categoria c, int col) {
        return col == 4;
    }

    @Override
    protected int alineacion(int col) {
        return col == 1 || col == 2 ? SwingConstants.LEFT : SwingConstants.CENTER;
    }

    @Override
    protected String resumen(List<BaseGastos.Categoria> l) {
        return l.size() + (l.size() == 1 ? " categoría" : " categorías");
    }

    @Override
    protected void abrir(BaseGastos.Categoria c) {
        editar(c);
    }

    private void editar(BaseGastos.Categoria c0) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        BaseGastos.Categoria c = c0 == null ? new BaseGastos.Categoria() : c0;
        JPanel t = Capa.tarjeta(500, 22, 30);
        t.add(Tema.tituloPantalla(c.id == 0 ? "Agregar Categoría" : "Actualizar Categoría"));
        t.add(Tema.espacio(12));
        JTextField nombre = Tema.campo("Ej. ASEO Y CAFETERÍA");
        nombre.setText(c.nombre);
        nombre.getAccessibleContext().setAccessibleName("Nombre");
        PanelGastos.agregar(t, "Nombre", nombre);
        JComboBox<String> estado = new JComboBox<>(new String[]{"ACTIVO", "NO ACTIVO"});
        Tema.estiloCombo(estado, String::valueOf);
        estado.setSelectedIndex(c.activa ? 0 : 1);
        estado.getAccessibleContext().setAccessibleName("Estado");
        PanelGastos.agregar(t, "Estado", estado);
        JComboBox<String> concepto = new JComboBox<>();
        concepto.addItem("Sin clasificar");
        for (String[] k : BaseGastos.CONCEPTOS_1001) {
            concepto.addItem(k[0] + " - " + k[1]);
            if (k[0].equals(c.concepto)) {
                concepto.setSelectedIndex(concepto.getItemCount() - 1);
            }
        }
        concepto.setMaximumRowCount(16);
        Tema.estiloCombo(concepto, String::valueOf);
        concepto.getAccessibleContext().setAccessibleName("Concepto DIAN formato 1001");
        PanelGastos.agregar(t, "Concepto DIAN – Formato 1001 (opcional)", concepto);
        JLabel nota = new JLabel("Se aplicará automáticamente a los gastos de esta categoría.");
        nota.setFont(Tema.texto(12.5f));
        nota.setForeground(Tema.GRIS_TEXTO);
        nota.setAlignmentX(LEFT_ALIGNMENT);
        t.add(nota);
        t.add(Tema.espacio(10));
        JComboBox<BaseGastos.Renta> renta = new JComboBox<>(BaseGastos.Renta.values());
        Tema.estiloCombo(renta, r -> r.texto.toUpperCase());
        renta.setSelectedItem(c.renta);
        renta.getAccessibleContext().setAccessibleName("Tratamiento en renta");
        PanelGastos.agregar(t, "Tratamiento en renta (opcional)", renta);
        JLabel error = PanelGastos.errorLabel();
        t.add(error);
        t.add(Tema.espacio(6));
        Tema.Boton si = Tema.botonPrimario(c.id == 0 ? "Agregar" : "Actualizar");
        Tema.Boton no = Tema.botonSecundario("Cancelar");
        JPanel f = Tema.fila(8, no, si);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        Capa capa = new Capa(this, t, true);
        Runnable hacer = () -> {
            String antes = c.nombre;
            try {
                c.nombre = nombre.getText();
                c.activa = estado.getSelectedIndex() == 0;
                int k = concepto.getSelectedIndex();
                c.concepto = k <= 0 ? "" : BaseGastos.CONCEPTOS_1001[k - 1][0];
                c.renta = (BaseGastos.Renta) renta.getSelectedItem();
                app.getGastos().guardarCategoria(c);
                capa.cerrar();
                recargar();
                Aviso.mostrar(this, Aviso.Tipo.BIEN, "Categoría guardada", c.nombre);
            } catch (IllegalArgumentException ex) {
                c.nombre = antes;
                error.setText(ex.getMessage());
            } catch (java.sql.SQLException ex) {
                c.nombre = antes;
                Ubicaciones.registrarError(ex);
                error.setText("No se pudo guardar: " + ex.getMessage());
            }
        };
        si.addActionListener(e -> hacer.run());
        nombre.addActionListener(e -> hacer.run());
        no.addActionListener(e -> capa.cerrar());
        capa.mostrar(nombre);
    }

    private void quitar(BaseGastos.Categoria c) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        Alerta.confirmar(this, "¿Quitar la categoría " + c.nombre + "?",
                "Si ya tiene gastos no se borra: queda inactiva y el historial no pierde nada.", "Sí, quitar", "Volver", () -> {
                    try {
                        boolean borrada = app.getGastos().quitarCategoria(c.id);
                        recargar();
                        Aviso.mostrar(this, Aviso.Tipo.BIEN, borrada ? "Categoría borrada" : "Categoría desactivada",
                                borrada ? c.nombre : c.nombre + " tiene gastos: quedó inactiva");
                    } catch (java.sql.SQLException ex) {
                        Ubicaciones.registrarError(ex);
                        Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo quitar", ex.getMessage(), null);
                    }
                });
    }

    private void activar(BaseGastos.Categoria c) {
        try {
            c.activa = true;
            app.getGastos().guardarCategoria(c);
            recargar();
        } catch (Exception ex) {
            Ubicaciones.registrarError(ex);
        }
    }
}
