package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Window;
import java.io.File;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;
import rutas.modelo.Ciudad;
import rutas.osm.DescargadorOSM;
import rutas.osm.ProcesadorOSM;

/** Ventana de espera mientras se descarga el mapa de una ciudad de OpenStreetMap. */
public final class DialogoDescarga {

    private DialogoDescarga() {
    }

    /** Descarga la ciudad. Devuelve true si salio bien. */
    public static boolean descargar(Window padre, Ciudad c, File usuario) {
        JDialog d = new JDialog(padre, "Descargando mapa", JDialog.ModalityType.APPLICATION_MODAL);
        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        JLabel titulo = new JLabel("Mapa de " + c.getNombre());
        titulo.setFont(Tema.subtitulo(16));
        titulo.setForeground(Tema.AZUL_PROFUNDO);
        JLabel estado = new JLabel("Conectando con OpenStreetMap...");
        estado.setFont(Tema.texto(12.5f));
        estado.setForeground(Tema.GRIS_OSCURO);
        JProgressBar barra = new JProgressBar();
        barra.setIndeterminate(true);
        JLabel nota = new JLabel("<html><div style='width:330px;color:#5B6675'>Se descargan las calles, sentidos, "
                + "semáforos y lugares. Puede tardar de 1 a 3 minutos. Datos © colaboradores de OpenStreetMap.</div></html>");
        nota.setFont(Tema.texto(11.5f));
        JPanel centro = new JPanel(new BorderLayout(0, 8));
        centro.setOpaque(false);
        centro.add(estado, BorderLayout.NORTH);
        centro.add(barra, BorderLayout.CENTER);
        p.add(titulo, BorderLayout.NORTH);
        p.add(centro, BorderLayout.CENTER);
        p.add(nota, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        d.pack();
        d.setLocationRelativeTo(padre);

        boolean[] exito = {false};
        SwingWorker<ProcesadorOSM.Resumen, String> trabajo = new SwingWorker<>() {
            @Override
            protected ProcesadorOSM.Resumen doInBackground() throws Exception {
                return DescargadorOSM.descargar(c, c.carpetaDescarga(usuario), this::publish);
            }

            @Override
            protected void process(List<String> mensajes) {
                estado.setText(mensajes.get(mensajes.size() - 1));
            }

            @Override
            protected void done() {
                d.dispose();
                try {
                    ProcesadorOSM.Resumen r = get();
                    exito[0] = true;
                    JOptionPane.showMessageDialog(padre, "Mapa de " + c.getNombre() + " listo:\n" + r,
                            "DomiRuta", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable causa = e.getCause() != null ? e.getCause() : e;
                    rutas.logica.Ubicaciones.registrarError(causa);
                    JOptionPane.showMessageDialog(padre, "No se pudo descargar el mapa de " + c.getNombre() + ".\n"
                            + causa.getMessage(), "DomiRuta", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        trabajo.execute();
        d.setVisible(true); // se bloquea hasta que termine la descarga
        return exito[0];
    }
}
