package rutas.vista;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.util.Locale;
import javax.swing.SwingUtilities;

/**
 * Elegir archivos con el explorador de Windows de verdad (el mismo de cualquier programa),
 * no con la imitacion de Swing (JFileChooser). Recuerda la ultima carpeta usada.
 */
public final class Archivos {

    private static String ultimaCarpeta = System.getProperty("user.home");

    private Archivos() {
    }

    /** Abre un archivo con una de las extensiones dadas (sin punto: "jpg", "png"). Devuelve null si cancela. */
    public static File abrir(Component padre, String titulo, String... extensiones) {
        FileDialog d = dialogo(padre, titulo, FileDialog.LOAD);
        // en Windows, "*.jpg;*.png" en el nombre hace de filtro; en otros sistemas sirve el filtro de abajo
        StringBuilder patron = new StringBuilder();
        for (String e : extensiones) {
            patron.append(patron.length() == 0 ? "" : ";").append("*.").append(e);
        }
        d.setFile(patron.toString());
        d.setFilenameFilter((dir, nombre) -> tieneExtension(nombre, extensiones));
        d.setVisible(true);
        return resultado(d);
    }

    /** Guarda con un nombre sugerido; si la persona no escribe la extension, se le agrega. Null si cancela. */
    public static File guardar(Component padre, String titulo, String nombreSugerido, String extension) {
        FileDialog d = dialogo(padre, titulo, FileDialog.SAVE);
        d.setFile(nombreSugerido);
        d.setVisible(true);
        File f = resultado(d);
        return f == null ? null : conExtension(f, extension);
    }

    /** "productos" -> "productos.csv"; "Lista.CSV" queda igual. */
    public static File conExtension(File f, String extension) {
        return tieneExtension(f.getName(), extension) ? f : new File(f.getParentFile(), f.getName() + "." + extension);
    }

    private static boolean tieneExtension(String nombre, String... extensiones) {
        String n = nombre.toLowerCase(Locale.ROOT);
        for (String e : extensiones) {
            if (n.endsWith("." + e.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static FileDialog dialogo(Component padre, String titulo, int modo) {
        Window w = padre == null ? null : SwingUtilities.getWindowAncestor(padre);
        FileDialog d = w instanceof Dialog ? new FileDialog((Dialog) w, titulo, modo)
                : new FileDialog(w instanceof Frame ? (Frame) w : null, titulo, modo);
        d.setDirectory(ultimaCarpeta);
        return d;
    }

    private static File resultado(FileDialog d) {
        if (d.getFile() == null) {
            return null; // cancelo
        }
        ultimaCarpeta = d.getDirectory();
        return new File(d.getDirectory(), d.getFile());
    }
}
