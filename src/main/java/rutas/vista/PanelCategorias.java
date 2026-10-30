package rutas.vista;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.io.File;
import java.sql.SQLException;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import rutas.logica.BaseTienda;
import rutas.modelo.Categoria;

/**
 * Categorias de la tienda (Granos, Bebidas, Aseo...) con su foto: son los botones
 * de arriba en Vender. Solo el administrador entra aqui (desde Productos).
 */
final class PanelCategorias {

    private final BaseTienda bd;
    private final Runnable alCambiar;
    private final DefaultListModel<Categoria> modelo = new DefaultListModel<>();
    private final JList<Categoria> lista = new Tema.Lista<>(modelo,
            "Aún no hay categorías. Cree la primera abajo (por ejemplo: Granos, Bebidas, Aseo).");
    private final JTextField campoNombre = Tema.campo("Ej. Granos");
    private final JTextField campoOrden = Tema.campo("1");
    private final FotoChica foto = new FotoChica();
    private final Tema.Parrafo mensaje = Tema.parrafo(" ", 480);
    private final Tema.Boton btnActiva = Tema.botonPequeno("Desactivar");
    private final JLabel titulo = Tema.seccion("Nueva categoría");
    private Categoria editando;
    private File fotoElegida;

    private PanelCategorias(BaseTienda bd, Runnable alCambiar) {
        this.bd = bd;
        this.alCambiar = alCambiar;
    }

    static void mostrar(JComponent dentroDe, Aplicacion app, Runnable alCambiar) {
        new PanelCategorias(app.getTienda(), alCambiar).abrir(dentroDe);
    }

    private void abrir(JComponent dentroDe) {
        JPanel t = Capa.tarjeta(560, 24, 28);
        t.add(Tema.tituloPantalla("Categorías"));
        t.add(Tema.parrafo("Son los botones con foto de arriba en Vender. El orden dice cuál va primero.", 500));
        t.add(Tema.espacio(10));
        lista.setCellRenderer(new Render());
        lista.getAccessibleContext().setAccessibleName("Lista de categorías");
        lista.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && lista.getSelectedValue() != null) {
                cargar(lista.getSelectedValue());
            }
        });
        JScrollPane sc = new JScrollPane(lista);
        sc.setBorder(Tema.bordeLista());
        sc.setAlignmentX(Component.LEFT_ALIGNMENT);
        sc.setPreferredSize(new Dimension(480, 180));
        sc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        t.add(sc);
        t.add(Tema.espacio(12));

        t.add(titulo);
        JPanel fila = new JPanel(new java.awt.BorderLayout(10, 0));
        fila.setOpaque(false);
        fila.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel campos = new JPanel();
        campos.setOpaque(false);
        campos.setLayout(new javax.swing.BoxLayout(campos, javax.swing.BoxLayout.Y_AXIS));
        campos.add(Tema.etiqueta("Nombre"));
        Tema.anchoCompleto(campoNombre);
        campoNombre.getAccessibleContext().setAccessibleName("Nombre de la categoría");
        campos.add(campoNombre);
        campos.add(Tema.espacio(8));
        campos.add(Tema.etiqueta("Orden"));
        Tema.anchoCompleto(campoOrden);
        campoOrden.getAccessibleContext().setAccessibleName("Orden");
        campos.add(campoOrden);
        fila.add(campos, java.awt.BorderLayout.CENTER);
        JPanel lado = new JPanel();
        lado.setOpaque(false);
        lado.setLayout(new javax.swing.BoxLayout(lado, javax.swing.BoxLayout.Y_AXIS));
        foto.setAlignmentX(Component.CENTER_ALIGNMENT);
        lado.add(foto);
        lado.add(Tema.espacio(6));
        Tema.Boton elegir = Tema.botonPequeno("Elegir foto…");
        elegir.setAlignmentX(Component.CENTER_ALIGNMENT);
        lado.add(elegir);
        fila.add(lado, java.awt.BorderLayout.EAST);
        fila.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        t.add(fila);
        t.add(Tema.espacio(8));
        mensaje.setFont(Tema.texto(13));
        t.add(mensaje);
        t.add(Tema.espacio(8));
        Tema.Boton guardar = Tema.botonPrimario("Guardar");
        Tema.Boton nueva = Tema.botonSecundario("Nueva");
        Tema.Boton cerrar = Tema.botonSecundario("Listo");
        t.add(Tema.fila(8, guardar, nueva, btnActiva, cerrar));

        Capa capa = new Capa(dentroDe, t, true);
        guardar.addActionListener(e -> guardar());
        campoNombre.addActionListener(e -> guardar());
        nueva.addActionListener(e -> limpiar());
        btnActiva.addActionListener(e -> cambiarActiva());
        elegir.addActionListener(e -> elegirFoto(t));
        cerrar.addActionListener(e -> capa.cerrar());
        capa.setAlCerrar(alCambiar);
        refrescar();
        limpiar();
        capa.mostrar(campoNombre);
    }

    private void refrescar() {
        modelo.clear();
        try {
            for (Categoria c : bd.listarCategorias(true)) {
                modelo.addElement(c);
            }
        } catch (SQLException e) {
            decir("No se pudieron leer: " + e.getMessage(), false);
        }
    }

    private void limpiar() {
        editando = null;
        fotoElegida = null;
        lista.clearSelection();
        titulo.setText("NUEVA CATEGORÍA");
        campoNombre.setText("");
        campoOrden.setText(String.valueOf(modelo.size() + 1));
        foto.ruta = null;
        foto.repaint();
        btnActiva.setVisible(false);
        mensaje.setText(" ");
        campoNombre.requestFocusInWindow();
    }

    private void cargar(Categoria c) {
        editando = c;
        fotoElegida = null;
        titulo.setText("EDITAR CATEGORÍA");
        campoNombre.setText(c.getNombre());
        campoOrden.setText(String.valueOf(c.getOrden()));
        foto.ruta = c.getFoto();
        foto.repaint();
        btnActiva.setVisible(true);
        btnActiva.setText(c.isActivo() ? "Desactivar" : "Activar");
        mensaje.setText(" ");
    }

    private void guardar() {
        try {
            int orden;
            try {
                orden = campoOrden.getText().isBlank() ? 0 : Integer.parseInt(campoOrden.getText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("El orden es un número (1, 2, 3...)");
            }
            Categoria c;
            if (editando == null) {
                c = bd.crearCategoria(campoNombre.getText(), null, orden);
            } else {
                c = new Categoria(editando.getId(), campoNombre.getText(), editando.getFoto(), orden, editando.isActivo());
                bd.editarCategoria(c);
            }
            editando = c;
            if (fotoElegida != null) {
                String ruta = PanelProductos.copiarFoto(fotoElegida, "categoria-" + c.getId());
                Fotos.olvidar(ruta);
                c = new Categoria(c.getId(), c.getNombre(), ruta, c.getOrden(), c.isActivo());
                bd.editarCategoria(c);
                fotoElegida = null;
            }
            refrescar();
            lista.setSelectedValue(c, true);
            cargar(c);
            decir("Guardada", true);
        } catch (IllegalArgumentException e) {
            decir(e.getMessage(), false);
        } catch (SQLException | java.io.IOException e) {
            decir("No se pudo guardar: " + e.getMessage(), false);
        }
    }

    private void cambiarActiva() {
        if (editando == null) {
            return;
        }
        try {
            Categoria c = new Categoria(editando.getId(), editando.getNombre(), editando.getFoto(), editando.getOrden(),
                    !editando.isActivo());
            bd.editarCategoria(c);
            refrescar();
            lista.setSelectedValue(c, true);
            cargar(c);
            decir(c.isActivo() ? "La categoría vuelve a verse en Vender" : "Ya no se ve en Vender (sus productos sí)", true);
        } catch (SQLException e) {
            decir("No se pudo guardar: " + e.getMessage(), false);
        }
    }

    private void elegirFoto(JComponent padre) {
        java.io.File f = Archivos.abrir(padre, "Elegir la foto de la categoría", "jpg", "jpeg", "png");
        if (f != null) {
            fotoElegida = f;
            foto.ruta = fotoElegida.getAbsolutePath();
            foto.repaint();
            decir("La foto se guarda al oprimir Guardar", true);
        }
    }

    private void decir(String texto, boolean bien) {
        mensaje.setForeground(bien ? Tema.EXITO_TEXTO : Tema.ERROR_TEXTO);
        mensaje.setText(texto);
    }

    /** Foto redonda de 72 px (o un circulo gris si no hay). */
    private static class FotoChica extends JPanel {
        String ruta;

        FotoChica() {
            setOpaque(false);
            setPreferredSize(new Dimension(76, 76));
            setMaximumSize(new Dimension(76, 76));
            getAccessibleContext().setAccessibleName("Foto de la categoría");
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int d = 72, x = (getWidth() - d) / 2, y = 2;
            Image img = Fotos.cargar(ruta, d, d);
            g.setColor(Tema.GRIS_CLARO);
            g.fillOval(x, y, d, d);
            if (img != null) {
                g.clip(new java.awt.geom.Ellipse2D.Double(x, y, d, d));
                Fotos.dibujar(g, img, x - 8, y - 8, d + 16, d + 16);
            } else {
                Fotos.paquete(g, x + d / 2, y + d / 2, 30);
            }
            g.dispose();
        }
    }

    private static class Render extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean foco) {
            Categoria c = (Categoria) value;
            return Tema.celda(list, c.getNombre(), Tema.textoFuerte(14), c.isActivo() ? Tema.GRIS_OSCURO : Tema.GRIS_TEXTO,
                    "Orden " + c.getOrden() + (c.getFoto() == null ? " · sin foto" : " · con foto")
                            + (c.isActivo() ? "" : " · Desactivada"), sel, Color.WHITE);
        }
    }
}
