package rutas.logica;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Donde estan los archivos del programa:
 *  - datos: mapas y configuracion que vienen con el programa (solo lectura).
 *  - usuario: lo que guarda cada usuario (clientes, historial, novedades...). En Windows va en
 *    %APPDATA%\DomiRuta para que funcione aunque el programa este instalado en Archivos de programa.
 */
public final class Ubicaciones {

    private Ubicaciones() {
    }

    /** Busca la carpeta datos: junto al programa o en la carpeta actual (NetBeans). */
    public static File carpetaDatos() {
        File actual = new File("datos");
        if (new File(actual, "ciudades.txt").exists()) {
            return actual;
        }
        try {
            File jar = new File(Ubicaciones.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (File d = jar.getParentFile(); d != null; d = d.getParentFile()) {
                File cand = new File(d, "datos");
                if (new File(cand, "ciudades.txt").exists()) {
                    return cand;
                }
            }
        } catch (URISyntaxException | SecurityException e) {
            // se usa la carpeta actual
        }
        return actual;
    }

    /** Carpeta de datos del usuario (se crea si no existe). */
    public static File carpetaUsuario() {
        String appdata = System.getenv("APPDATA");
        File f = appdata != null ? new File(appdata, "DomiRuta")
                : new File(System.getProperty("user.home"), ".domiruta");
        f.mkdirs();
        return f;
    }

    /** Ultima ciudad usada (por defecto Riohacha). */
    public static String ultimaCiudad() {
        File f = new File(carpetaUsuario(), "ciudad.txt");
        try {
            if (f.exists()) {
                List<String> l = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                if (!l.isEmpty() && !l.get(0).isBlank()) {
                    return l.get(0).trim();
                }
            }
        } catch (IOException e) {
            // por defecto
        }
        return "riohacha";
    }

    public static void guardarCiudad(String clave) {
        try {
            Files.write(new File(carpetaUsuario(), "ciudad.txt").toPath(), List.of(clave), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // no es grave
        }
    }

    /** Escribe un error en errores.log para poder dar soporte. */
    public static void registrarError(Throwable t) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(new File(carpetaUsuario(), "errores.log"),
                StandardCharsets.UTF_8, true))) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            pw.println("---- " + LocalDateTime.now() + " ----");
            pw.println(sw);
        } catch (IOException e) {
            // si no se puede escribir el registro no hay mas que hacer
        }
    }
}
