package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import rutas.logica.NubeRepartidor;
import rutas.modelo.Ruta;
import rutas.modelo.Salida;

/**
 * Conversación con el repartidor de una salida. Lo que él escribe llega con la revisión de cada
 * 15 segundos (y al abrir esta ventana se revisa de una vez).
 */
final class CapaMensajes {

    /** Respuestas rápidas de la tienda (un clic y se mandan). */
    static final String[] RAPIDOS = {"Ya te llamo", "Espera ahí, ya te ayudo", "Devuélvete a la tienda", "Listo, gracias"};

    private CapaMensajes() {
    }

    static void abrir(JComponent dentroDe, Aplicacion app, Salida s, Seguimiento seguimiento) {
        JPanel t = Capa.tarjeta(560, 24, 28);
        JLabel titulo = new JLabel("Mensajes con " + (s.repartidor.isEmpty() ? "el repartidor" : s.repartidor));
        titulo.setFont(Tema.subtitulo(18));
        titulo.setForeground(Tema.AZUL_PROFUNDO);
        titulo.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        t.add(titulo);
        JLabel sub = new JLabel("Salida de las " + Ruta.FORMATO_HORA.format(s.creada));
        sub.setFont(Tema.texto(13));
        sub.setForeground(Tema.GRIS_TEXTO);
        sub.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        t.add(sub);
        t.add(Tema.espacio(10));

        JPanel lista = new JPanel();
        lista.setLayout(new BoxLayout(lista, BoxLayout.Y_AXIS));
        lista.setBackground(Tema.GRIS_CLARO);
        lista.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setBackground(Tema.GRIS_CLARO);
        arriba.add(lista, BorderLayout.NORTH);
        JScrollPane sc = new JScrollPane(arriba);
        sc.setBorder(Tema.bordeLista());
        sc.getVerticalScrollBar().setUnitIncrement(16);
        sc.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        sc.setPreferredSize(new Dimension(500, 300));
        sc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 300));
        t.add(sc);
        t.add(Tema.espacio(10));

        JTextField campo = Tema.campo("Escriba un mensaje (máximo 300 letras)");
        Tema.anchoCompleto(campo);
        Tema.Boton enviar = Tema.botonPrimario("Enviar");
        JPanel rapidos = Tema.fila(6);
        JLabel error = new JLabel(" ");
        error.setFont(Tema.textoFuerte(12.5f));
        error.setForeground(Tema.ERROR_TEXTO);
        error.setAlignmentX(JComponent.LEFT_ALIGNMENT);

        Runnable pintar = () -> {
            lista.removeAll();
            if (s.mensajes.isEmpty()) {
                JLabel v = new JLabel("Todavía no hay mensajes.");
                v.setFont(Tema.texto(13));
                v.setForeground(Tema.GRIS_TEXTO);
                lista.add(v);
            }
            for (Salida.Mensaje m : s.mensajes) {
                lista.add(burbuja(m));
                lista.add(Tema.espacio(6));
            }
            lista.revalidate();
            lista.repaint();
            SwingUtilities.invokeLater(() -> {
                javax.swing.JScrollBar b = sc.getVerticalScrollBar();
                b.setValue(b.getMaximum());
            });
        };

        // al abrir se dan por leídos
        boolean habia = s.mensajesSinLeer() > 0;
        for (Salida.Mensaje m : s.mensajes) {
            m.leido = true;
        }
        NubeRepartidor nube = app.getNube();
        if (habia) {
            try {
                app.guardarSalida(s);
            } catch (java.io.IOException ex) {
                // no es grave: se vuelve a marcar la próxima vez
            }
            app.avisar();
            if (nube != null) {
                new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        nube.marcarLeidos(s.uuidNube);
                        return null;
                    }
                }.execute();
            }
        }

        java.util.function.Consumer<String> mandar = texto -> {
            String txt = texto == null ? "" : texto.trim();
            if (txt.isEmpty()) {
                return;
            }
            if (nube == null) {
                error.setText("Sin conexión configurada (Configuración › Repartidor).");
                return;
            }
            enviar.setEnabled(false);
            error.setText(" ");
            new SwingWorker<Salida.Mensaje, Void>() {
                @Override
                protected Salida.Mensaje doInBackground() throws Exception {
                    return nube.escribir(s.uuidNube, txt);
                }

                @Override
                protected void done() {
                    enviar.setEnabled(true);
                    try {
                        Salida.Mensaje m = get();
                        if (s.mensajes.stream().noneMatch(x -> x.idNube == m.idNube)) {
                            s.mensajes.add(m); // en orden aunque la revisión haya traído otros antes
                            s.mensajes.sort(java.util.Comparator.comparingLong(x -> x.idNube));
                        }
                        app.guardarSalida(s);
                        if (txt.equals(campo.getText().trim())) {
                            campo.setText("");
                        }
                        pintar.run();
                        campo.requestFocusInWindow();
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() == null ? ex : ex.getCause();
                        error.setText(c.getMessage()); // lo escrito se queda en el campo
                    }
                }
            }.execute();
        };

        for (String r : RAPIDOS) {
            Tema.Boton b = Tema.botonPequeno(r);
            b.addActionListener(a -> mandar.accept(r));
            rapidos.add(b);
        }
        t.add(rapidos);
        t.add(Tema.espacio(8));
        JPanel escribir = new JPanel(new BorderLayout(8, 0));
        escribir.setOpaque(false);
        escribir.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        escribir.add(campo, BorderLayout.CENTER);
        escribir.add(enviar, BorderLayout.EAST);
        escribir.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        t.add(escribir);
        t.add(Tema.espacio(4));
        t.add(error);
        t.add(Tema.espacio(8));
        Tema.Boton cerrar = Tema.botonSecundario("Cerrar");
        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        pie.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        pie.add(cerrar, BorderLayout.EAST);
        t.add(pie);

        enviar.addActionListener(a -> mandar.accept(campo.getText()));
        campo.addActionListener(a -> mandar.accept(campo.getText()));
        Capa capa = new Capa(dentroDe, t, true);
        Runnable alCambiar = () -> {
            boolean nuevos = false;
            for (Salida.Mensaje m : s.mensajes) {
                if (!m.leido) {
                    m.leido = true;
                    nuevos = true;
                }
            }
            if (nuevos && nube != null) {
                new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        nube.marcarLeidos(s.uuidNube);
                        return null;
                    }
                }.execute();
            }
            pintar.run();
        };
        app.alCambiar(alCambiar);
        capa.setAlCerrar(() -> app.quitarOyente(alCambiar));
        cerrar.addActionListener(a -> capa.cerrar());
        pintar.run();
        capa.mostrar(campo);
        if (seguimiento != null) {
            seguimiento.revisar(); // por si escribió hace poco
        }
    }

    /** Un mensaje: los de la tienda a la derecha en azul suave; los del repartidor a la izquierda en blanco. */
    private static JComponent burbuja(Salida.Mensaje m) {
        JPanel fila = new JPanel(new FlowLayout(m.deTienda ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        fila.setOpaque(false);
        fila.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        Color fondo = m.deTienda ? Tema.AZUL_SUAVE : Color.WHITE;
        JPanel b = new JPanel() {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                g.setColor(fondo);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tema.ARCO, Tema.ARCO);
                g.setColor(Tema.BORDE);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tema.ARCO, Tema.ARCO);
                g.dispose();
            }
        };
        b.setOpaque(false);
        b.setLayout(new BoxLayout(b, BoxLayout.Y_AXIS));
        b.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
        JLabel quien = new JLabel(m.deTienda ? "Tienda" : "Repartidor");
        quien.setFont(Tema.textoFuerte(11.5f));
        quien.setForeground(m.deTienda ? Tema.AZUL : Tema.AVISO_TEXTO);
        b.add(quien);
        // el domiciliario escribe desde el celular: puede traer emojis (👍 🛵)
        TextoEmoji p = new TextoEmoji(m.texto, Tema.texto(14), Tema.GRIS_OSCURO, 300);
        b.add(p);
        JLabel h = new JLabel(Ruta.FORMATO_HORA.format(m.hora));
        h.setFont(Tema.texto(11.5f));
        h.setForeground(Tema.GRIS_TEXTO);
        b.add(h);
        fila.add(b);
        fila.setMaximumSize(new Dimension(Integer.MAX_VALUE, fila.getPreferredSize().height));
        return fila;
    }
}
