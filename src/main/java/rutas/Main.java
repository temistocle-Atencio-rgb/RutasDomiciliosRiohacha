package rutas;

import java.io.File;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import rutas.logica.Ubicaciones;
import rutas.modelo.Ciudad;
import rutas.vista.Aplicacion;
import rutas.vista.DialogoDescarga;
import rutas.vista.Tema;
import rutas.vista.VentanaPrincipal;

/**
 * DomiRuta: optimizador de rutas de entrega para domicilios en La Guajira.
 * Proyecto final de Estructuras de Datos II.
 */
public class Main {

    private static long ultimoAvisoError;

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // si falla se queda el aspecto por defecto de Swing
        }
        // cualquier error inesperado queda en errores.log y se avisa sin cerrar el programa
        Thread.setDefaultUncaughtExceptionHandler((hilo, error) -> {
            Ubicaciones.registrarError(error);
            long ahora = System.currentTimeMillis();
            if (ahora - ultimoAvisoError > 5000) {
                ultimoAvisoError = ahora;
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                        "Ocurrió un error inesperado. El trabajo no se perdió.\nEl detalle quedó en "
                        + new File(Ubicaciones.carpetaUsuario(), "errores.log"), "DomiRuta", JOptionPane.WARNING_MESSAGE));
            }
        });
        Tema.cargarFuentes();
        SwingUtilities.invokeLater(() -> abrir(Ubicaciones.ultimaCiudad()));
    }

    /** Abre la ventana con la ciudad indicada (la descarga si todavia no esta). */
    public static void abrir(String clave) {
        File datos = Ubicaciones.carpetaDatos();
        File usuario = Ubicaciones.carpetaUsuario();
        try {
            List<Ciudad> ciudades = Ciudad.cargar(new File(datos, "ciudades.txt"));
            Ciudad ciudad = ciudades.get(0);
            for (Ciudad c : ciudades) {
                if (c.getClave().equals(clave)) {
                    ciudad = c;
                }
            }
            if (!ciudad.estaDisponible(datos, usuario) && !DialogoDescarga.descargar(null, ciudad, usuario)) {
                if (!ciudad.getClave().equals(ciudades.get(0).getClave())) {
                    abrir(ciudades.get(0).getClave());
                    return;
                }
                System.exit(1);
            }
            Aplicacion app = new Aplicacion(datos, ciudad, usuario);
            Ubicaciones.guardarCiudad(ciudad.getClave());
            new VentanaPrincipal(app, ciudades).setVisible(true);
        } catch (Exception e) {
            Ubicaciones.registrarError(e);
            JOptionPane.showMessageDialog(null, "No se pudieron cargar los datos del mapa:\n" + e.getMessage()
                    + "\n\nVerifique que la carpeta datos esté junto al programa.", "DomiRuta", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
}
