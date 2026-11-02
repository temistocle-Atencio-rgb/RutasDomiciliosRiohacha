package rutas.vista;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import rutas.logica.BaseTienda;
import rutas.logica.Ubicaciones;
import rutas.modelo.Categoria;

/**
 * Agregar o actualizar una categoria (capa encima de la lista): imagen a la izquierda (archivo,
 * arrastrar o direccion de internet) y a la derecha nombre, direccion de imagen, orden en Vender y estado.
 */
final class FormularioCategoria {

    private static final long MAXIMO_FOTO = 1024 * 1024;

    private final JComponent panel;
    private final BaseTienda bd;
    private final Runnable alGuardar;
    private final Categoria editando;

    private final JTextField campoNombre = Tema.campo("Ej. Bebidas");
    private final Controles.AreaTexto areaUrl = new Controles.AreaTexto("Dirección de internet de la imagen (https://…)", 4);
    private final JTextField campoOrden = Controles.campoNumero("1");
    private final JComboBox<String> comboEstado = new JComboBox<>(new String[]{"ACTIVO", "INACTIVO"});
    private final ZonaImagen zona = new ZonaImagen();
    private final Tema.Parrafo lblForm = Tema.parrafo(" ", 520);
    private File fotoElegida;
    private byte[] fotoDeUrl;
    private String urlBajada;
    private boolean bajando;
    private final Timer esperaUrl = new Timer(700, e -> bajarVistaUrl(false));
    private Capa capa;

    private FormularioCategoria(JComponent panel, BaseTienda bd, Categoria c, Runnable alGuardar) {
        this.panel = panel;
        this.bd = bd;
        this.editando = c;
        this.alGuardar = alGuardar;
        esperaUrl.setRepeats(false);
        lblForm.setFont(Tema.texto(13));
    }

    static void abrir(JComponent panel, BaseTienda bd, Categoria c, int siguienteOrden, Runnable alGuardar) {
        if (Capa.hayAbierta(panel)) {
            return;
        }
        FormularioCategoria f = new FormularioCategoria(panel, bd, c, alGuardar);
        f.armar();
        f.cargar(siguienteOrden);
        f.capa.mostrar(f.campoNombre);
    }

    private void armar() {
        JPanel modal = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(820, super.getPreferredSize().height);
            }
        };
        modal.setOpaque(false);

        JPanel cabeza = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                g.setColor(Tema.AZUL);
                g.fillRoundRect(0, 0, getWidth(), getHeight() + Tema.ARCO, Tema.ARCO, Tema.ARCO);
                g.dispose();
            }
        };
        cabeza.setOpaque(false);
        cabeza.setBorder(BorderFactory.createEmptyBorder(11, 20, 11, 12));
        JLabel titulo = new JLabel(editando == null ? "Agregar categoría" : "Actualizar categoría");
        titulo.setFont(Tema.subtitulo(20));
        titulo.setForeground(Tema.BLANCO);
        cabeza.add(titulo, BorderLayout.WEST);
        JButton cerrar = new BotonCerrar();
        cerrar.addActionListener(e -> capa.cerrar());
        cabeza.add(cerrar, BorderLayout.EAST);
        modal.add(cabeza, BorderLayout.NORTH);

        JPanel cuerpo = new JPanel(new GridBagLayout());
        cuerpo.setOpaque(false);
        cuerpo.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.NORTH;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 0, 24);
        cuerpo.add(tarjetaImagen(), g);
        g.weightx = 1;
        g.insets = new Insets(0, 0, 0, 0);
        cuerpo.add(campos(), g);
        modal.add(cuerpo, BorderLayout.CENTER);

        JPanel pie = new JPanel(new BorderLayout(16, 0));
        pie.setOpaque(false);
        pie.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Tema.BORDE),
                BorderFactory.createEmptyBorder(10, 20, 10, 20)));
        pie.add(lblForm, BorderLayout.CENTER);
        Tema.Boton guardar = Tema.botonAccion(editando == null ? "Agregar" : "Actualizar");
        Tema.Boton cancelar = Tema.botonSecundario("Cancelar");
        pie.add(Tema.fila(8, cancelar, guardar), BorderLayout.EAST);
        modal.add(pie, BorderLayout.SOUTH);

        capa = new Capa(panel, modal, true);
        capa.setAlCerrar(esperaUrl::stop);
        guardar.addActionListener(e -> guardar());
        cancelar.addActionListener(e -> capa.cerrar());
        campoNombre.addActionListener(e -> guardar());
    }

    private JComponent tarjetaImagen() {
        Tema.Tarjeta t = new Tema.Tarjeta();
        t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        t.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JLabel tit = new JLabel("IMAGEN DE PRESENTACIÓN", SwingConstants.CENTER);
        tit.setFont(Tema.subtitulo(13));
        tit.setForeground(Tema.GRIS_OSCURO);
        tit.setAlignmentX(Component.CENTER_ALIGNMENT);
        t.add(tit);
        t.add(Tema.espacio(10));
        zona.setAlignmentX(Component.CENTER_ALIGNMENT);
        t.add(zona);
        t.add(Tema.espacio(10));
        Tema.Boton subir = Tema.botonPrimario("Subir imagen");
        subir.setIcon(Iconos.de("foto", 20, Tema.BLANCO));
        subir.setFont(Tema.textoFuerte(13));
        subir.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
        subir.setAlignmentX(Component.CENTER_ALIGNMENT);
        subir.setMaximumSize(new Dimension(Integer.MAX_VALUE, subir.getPreferredSize().height));
        subir.addActionListener(e -> elegirFoto());
        t.add(subir);
        t.add(Tema.espacio(6));
        JLabel n = new JLabel("JPG o PNG. Máximo 1 MB.", SwingConstants.CENTER);
        n.setFont(Tema.texto(12));
        n.setForeground(Tema.GRIS_TEXTO);
        n.setAlignmentX(Component.CENTER_ALIGNMENT);
        t.add(n);
        JPanel envoltura = new JPanel(new BorderLayout());
        envoltura.setOpaque(false);
        envoltura.add(t, BorderLayout.NORTH);
        envoltura.setPreferredSize(new Dimension(300, t.getPreferredSize().height));
        return envoltura;
    }

    private JComponent campos() {
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.add(etiquetado("Nombre", campoNombre));
        col.add(etiquetado("Dirección de imagen", areaUrl));
        Tema.Parrafo ayuda = Tema.parrafo("También puede pegar una dirección de internet si no desea subir una imagen.",
                380);
        ayuda.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 6));
        col.add(ayuda);
        areaUrl.getArea().getDocument().addDocumentListener(PanelProductos.alCambiar(esperaUrl::restart));
        JPanel fila = new JPanel(new java.awt.GridLayout(1, 2, 16, 0));
        fila.setOpaque(false);
        fila.setAlignmentX(Component.LEFT_ALIGNMENT);
        fila.add(etiquetado("Orden en Vender", campoOrden));
        Tema.estiloCombo(comboEstado, String::valueOf);
        fila.add(etiquetado("Estado", comboEstado));
        fila.setMaximumSize(new Dimension(Integer.MAX_VALUE, fila.getPreferredSize().height));
        col.add(fila);
        Tema.Parrafo ayudaOrden = Tema.parrafo("El orden dice cuál botón sale primero arriba en Vender (1, 2, 3…)."
                + " Inactiva: no sale en Vender.", 380);
        ayudaOrden.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 6));
        col.add(ayudaOrden);
        return col;
    }

    private static JPanel etiquetado(String etiqueta, JComponent c) {
        JPanel p = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel l = new JLabel(etiqueta);
        l.setFont(Tema.textoFuerte(12.5f));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        l.setLabelFor(c instanceof javax.swing.JScrollPane ? ((javax.swing.JScrollPane) c).getViewport().getView() : c);
        FormularioProducto.compactar(c);
        if (c instanceof JTextField || c instanceof JComboBox) {
            c.getAccessibleContext().setAccessibleName(etiqueta);
        }
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (!(c instanceof javax.swing.JScrollPane)) {
            c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        }
        p.add(l);
        p.add(c);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private void cargar(int siguienteOrden) {
        if (editando == null) {
            campoOrden.setText(String.valueOf(siguienteOrden));
            comboEstado.setSelectedIndex(0);
            return;
        }
        campoNombre.setText(editando.getNombre());
        campoOrden.setText(String.valueOf(editando.getOrden()));
        comboEstado.setSelectedIndex(editando.isActivo() ? 0 : 1);
        if (editando.getFoto() != null && new File(editando.getFoto()).isFile()) {
            zona.setImagen(new ImageIcon(editando.getFoto()).getImage());
        }
        urlBajada = "";
        esperaUrl.stop();
    }

    private void guardar() {
        try {
            String nombre = campoNombre.getText().trim();
            if (nombre.isEmpty()) {
                throw new IllegalArgumentException("Escriba el nombre de la categoría");
            }
            int orden;
            try {
                orden = campoOrden.getText().isBlank() ? 0 : Integer.parseInt(campoOrden.getText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("El orden es un número (1, 2, 3…)");
            }
            boolean mismoNombre = editando != null && editando.getNombre().equalsIgnoreCase(nombre);
            for (Categoria otra : mismoNombre ? List.<Categoria>of() : bd.listarCategorias(true)) {
                if (otra.getNombre().equalsIgnoreCase(nombre) && (editando == null || otra.getId() != editando.getId())) {
                    throw new IllegalArgumentException("Ya hay una categoría llamada «" + otra.getNombre() + "»");
                }
            }
            String url = areaUrl.getText().trim();
            byte[] desdeUrl = null;
            if (fotoElegida == null && !url.isEmpty()) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    throw new IllegalArgumentException("La dirección de imagen debe empezar por http:// o https://");
                }
                if (url.equals(urlBajada) && fotoDeUrl != null) {
                    desdeUrl = fotoDeUrl;
                } else {
                    if (!bajando) {
                        bajarVistaUrl(true);
                    }
                    decir("Espere a que cargue la imagen de la dirección y oprima «"
                            + (editando == null ? "Agregar" : "Actualizar") + "» otra vez.", false);
                    lblForm.setForeground(Tema.AVISO_TEXTO);
                    return;
                }
            }
            boolean activo = comboEstado.getSelectedIndex() == 0;
            Categoria c;
            if (editando == null) {
                c = bd.crearCategoria(nombre, null, orden);
                if (!activo) {
                    c = new Categoria(c.getId(), nombre, null, orden, false);
                    bd.editarCategoria(c);
                }
            } else {
                c = new Categoria(editando.getId(), nombre, editando.getFoto(), orden, activo);
                bd.editarCategoria(c);
            }
            String ruta = null;
            if (fotoElegida != null) {
                ruta = PanelProductos.copiarFoto(fotoElegida, "categoria-" + c.getId());
            } else if (desdeUrl != null) {
                ruta = guardarBytes(desdeUrl, "categoria-" + c.getId());
            }
            if (ruta != null) {
                Fotos.olvidar(ruta);
                bd.editarCategoria(new Categoria(c.getId(), c.getNombre(), ruta, c.getOrden(), c.isActivo()));
            }
            capa.cerrar();
            alGuardar.run();
            Aviso.mostrar(panel, Aviso.Tipo.BIEN, editando == null ? "Categoría agregada" : "Cambios guardados", nombre);
        } catch (IllegalArgumentException e) {
            decir(e.getMessage(), false);
        } catch (SQLException | IOException e) {
            decir("No se pudo guardar: " + e.getMessage(), false);
        }
    }

    /** Guarda la imagen bajada de internet en la carpeta de fotos del programa. */
    static String guardarBytes(byte[] datos, String nombreBase) throws IOException {
        boolean png = datos.length > 3 && (datos[0] & 0xFF) == 0x89 && datos[1] == 'P';
        File carpeta = new File(Ubicaciones.carpetaUsuario(), "fotos");
        carpeta.mkdirs();
        File destino = new File(carpeta, nombreBase + (png ? ".png" : ".jpg"));
        Files.write(destino.toPath(), datos);
        return destino.getAbsolutePath();
    }

    private void elegirFoto() {
        File f = Archivos.abrir(panel, "Elegir la imagen de la categoría", "jpg", "jpeg", "png");
        if (f != null) {
            usarFoto(f);
        }
    }

    private void usarFoto(File f) {
        if (f.length() > MAXIMO_FOTO) {
            decir("Esa imagen pesa " + (f.length() / 1024) + " KB. El máximo es 1 MB.", false);
            return;
        }
        Image img;
        try {
            // la misma revision que al guardar (por el contenido): asi no falla despues de crear la categoria
            img = javax.imageio.ImageIO.read(new ByteArrayInputStream(FormularioProducto.leerImagenLocal(f)));
        } catch (IllegalArgumentException | IOException e) {
            decir(e instanceof IllegalArgumentException ? e.getMessage() : "No se pudo abrir esa imagen. Use JPG o PNG.", false);
            return;
        }
        fotoElegida = f;
        zona.setImagen(img);
        decir("La imagen se guarda al oprimir «" + (editando == null ? "Agregar" : "Actualizar") + "»", true);
    }

    private void bajarVistaUrl(boolean forzar) {
        String url = areaUrl.getText().trim();
        if (fotoElegida != null || url.isEmpty() || (url.equals(urlBajada) && (!forzar || fotoDeUrl != null))) {
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            decir("La dirección de imagen debe empezar por http:// o https://", false);
            return;
        }
        decir("Abriendo la imagen de la dirección…", true);
        lblForm.setForeground(Tema.GRIS_TEXTO);
        bajando = true;
        new SwingWorker<byte[], Void>() {
            @Override
            protected byte[] doInBackground() {
                return FormularioProducto.bajarImagen(url);
            }

            @Override
            protected void done() {
                bajando = false;
                if (!url.equals(areaUrl.getText().trim()) || fotoElegida != null) {
                    return;
                }
                try {
                    byte[] b = get();
                    fotoDeUrl = b;
                    urlBajada = url;
                    zona.setImagen(javax.imageio.ImageIO.read(new ByteArrayInputStream(b)));
                    decir("Imagen lista. Se guarda al oprimir «" + (editando == null ? "Agregar" : "Actualizar") + "»", true);
                } catch (Exception e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    decir(c.getMessage() == null ? "No se pudo abrir la imagen de esa dirección" : c.getMessage(), false);
                }
            }
        }.execute();
    }

    private void decir(String texto, boolean bien) {
        lblForm.setForeground(bien ? Tema.EXITO_TEXTO : Tema.ERROR_TEXTO);
        lblForm.setText(texto);
    }

    /** X blanca de la barra azul. */
    private static class BotonCerrar extends JButton {
        BotonCerrar() {
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(34, 34));
            setToolTipText("Cerrar sin guardar (Esc)");
            getAccessibleContext().setAccessibleName("Cerrar");
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth(), h = getHeight();
            if (getModel().isRollover() || isFocusOwner()) {
                g.setColor(Tema.AZUL_PROFUNDO);
                g.fillRoundRect(0, 0, w, h, Tema.ARCO, Tema.ARCO);
            }
            g.setColor(Tema.BLANCO);
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int m = 11;
            g.drawLine(m, m, w - m, h - m);
            g.drawLine(w - m, m, m, h - m);
            g.dispose();
        }
    }

    /** Recuadro de la imagen: muestra la foto o la caja; se puede soltar un archivo o hacer clic. */
    private class ZonaImagen extends JPanel {
        private Image imagen;

        ZonaImagen() {
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Arrastre aquí la imagen o haga clic para elegirla");
            getAccessibleContext().setAccessibleName("Imagen de la categoría");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    elegirFoto();
                }
            });
            setTransferHandler(new javax.swing.TransferHandler() {
                @Override
                public boolean canImport(TransferSupport s) {
                    return s.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                }

                @Override
                public boolean importData(TransferSupport s) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<File> archivos = (List<File>) s.getTransferable()
                                .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                        for (File f : archivos) {
                            String n = f.getName().toLowerCase();
                            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) {
                                usarFoto(f);
                                return true;
                            }
                        }
                        decir("Esa no es una imagen JPG o PNG", false);
                    } catch (java.awt.datatransfer.UnsupportedFlavorException | IOException e) {
                        decir("No se pudo leer el archivo", false);
                    }
                    return false;
                }
            });
        }

        void setImagen(Image img) {
            imagen = img;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(250, 196);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 196);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth(), h = getHeight();
            g.setColor(Tema.BLANCO);
            g.fillRoundRect(0, 0, w - 1, h - 1, Tema.ARCO, Tema.ARCO);
            g.setColor(Tema.BORDE_CONTROL);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
            g.drawRoundRect(0, 0, w - 1, h - 1, Tema.ARCO, Tema.ARCO);
            int cajaAlto = 46, cx = 12, cy = h - cajaAlto - 12, cw = w - 24;
            int arriba = 12, altoImagen = cy - arriba - 10;
            if (imagen != null && imagen.getWidth(null) > 0) {
                int iw = imagen.getWidth(null), ih = imagen.getHeight(null);
                double e = Math.min((w - 40.0) / iw, (double) altoImagen / ih);
                int dw = (int) (iw * e), dh = (int) (ih * e);
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(imagen, (w - dw) / 2, arriba + (altoImagen - dh) / 2, dw, dh, null);
            } else {
                int t = Math.min(96, altoImagen - 8);
                Iconos.dibujarColor(g, "/imagenes/caja.png", (w - t) / 2, arriba + (altoImagen - t) / 2, t);
            }
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(0xF5F8FC));
            g.fillRoundRect(cx, cy, cw, cajaAlto, Tema.ARCO, Tema.ARCO);
            g.setColor(Tema.BORDE);
            g.drawRoundRect(cx, cy, cw, cajaAlto, Tema.ARCO, Tema.ARCO);
            int r = 22, ox = cx + 14, oy = cy + (cajaAlto - r) / 2;
            g.setColor(Tema.AZUL);
            g.fillOval(ox, oy, r, r);
            g.setColor(Tema.BLANCO);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(ox + r / 2, oy + 6, ox + r / 2, oy + r - 6);
            g.drawLine(ox + 6, oy + r / 2, ox + r - 6, oy + r / 2);
            g.setFont(Tema.textoFuerte(11.5f));
            g.setColor(Tema.AZUL_PROFUNDO);
            java.awt.FontMetrics fm = g.getFontMetrics();
            String a = "Arrastre aquí o haga clic", b = "para seleccionar";
            int tx = ox + r + 8, ancho = cx + cw - tx - 8;
            g.drawString(a, tx + (ancho - fm.stringWidth(a)) / 2, cy + cajaAlto / 2 - 3);
            g.drawString(b, tx + (ancho - fm.stringWidth(b)) / 2, cy + cajaAlto / 2 + fm.getAscent() - 1);
            g.dispose();
        }
    }
}
