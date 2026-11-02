package rutas;

import java.io.File;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import rutas.logica.BaseTienda;
import rutas.logica.Catalogo;
import rutas.logica.Ingreso;
import rutas.logica.Sesion;
import rutas.logica.Ubicaciones;
import rutas.modelo.Ciudad;
import rutas.vista.Aplicacion;
import rutas.vista.DialogoDescarga;
import rutas.vista.DialogoIngreso;
import rutas.vista.Tema;
import rutas.vista.VentanaPrincipal;

/**
 * DomiRuta: optimizador de rutas de entrega para domicilios en La Guajira.
 * Proyecto final de Estructuras de Datos II.
 */
public class Main {

    private static long ultimoAvisoError;

    // la tienda se abre una sola vez: cambiar de municipio no vuelve a pedir ingreso
    private static BaseTienda tienda;
    private static Catalogo catalogo;
    private static Ingreso ingreso;
    private static Sesion sesion;

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
        SwingUtilities.invokeLater(() -> {
            abrirTienda();
            pedirIngreso();
            rutas.vista.Lector.get().empezar(); // celular por cable y lectores USB, para todas las ventanas
            abrir(Ubicaciones.ultimaCiudad());
        });
    }

    /** Abre la base de la tienda; sin ella no hay ingreso, asi que el programa se cierra. */
    private static void abrirTienda() {
        File archivo = new File(Ubicaciones.carpetaUsuario(), "tienda.db");
        try {
            tienda = new BaseTienda(archivo);
            catalogo = new Catalogo(tienda);
            ingreso = new Ingreso(tienda);
            Runtime.getRuntime().addShutdownHook(new Thread(tienda::close));
        } catch (Exception e) {
            Ubicaciones.registrarError(e);
            JOptionPane.showMessageDialog(null, "No se pudo abrir la base de la tienda:\n" + archivo
                    + "\n\n" + e.getMessage() + "\n\nRevise que DomiRuta no esté abierto dos veces.",
                    "DomiRuta", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    /** Pide usuario y contraseña; si la persona cierra la ventana, el programa termina. */
    private static void pedirIngreso() {
        try {
            sesion = DialogoIngreso.pedir(null, tienda, ingreso);
        } catch (Exception e) {
            Ubicaciones.registrarError(e);
            JOptionPane.showMessageDialog(null, "No se pudo leer la base de la tienda:\n" + e.getMessage(),
                    "DomiRuta", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        if (sesion == null) {
            System.exit(0);
        }
    }

    /**
     * Boton "Salir": cierra la ventana y pide ingreso otra vez. La aplicacion no se vuelve a
     * cargar: el mapa y los pedidos en cola siguen ahi para el siguiente turno.
     */
    public static void cerrarSesion(java.awt.Window ventana, Aplicacion app, List<Ciudad> ciudades) {
        ventana.dispose();
        app.quitarOyentes();
        sesion = null;
        pedirIngreso();
        app.setTienda(tienda, catalogo, sesion);
        new VentanaPrincipal(app, ciudades).setVisible(true);
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
            app.setTienda(tienda, catalogo, sesion);
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
