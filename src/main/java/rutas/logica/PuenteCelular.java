package rutas.logica;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Habla con el celular por el cable USB usando adb (las herramientas de Android).
 *
 * Cada 5 segundos mira si hay un celular conectado con la depuracion USB activa. La primera vez que
 * ve un celular pregunta en el computador si usarlo como lector (puede ser el de un cliente que solo
 * lo conecto a cargar): la respuesta se recuerda. Con un celular aprobado hace "adb reverse" del
 * puerto del lector (asi el localhost del celular llega a este computador), le instala la app
 * DomiRuta Lector si no la tiene y la abre. Al desconectar y volver a conectar, lo hace otra vez.
 */
public final class PuenteCelular {

    /** Paquete y pantalla de la app del celular. */
    public static final String PAQUETE = "co.domiruta.lector";
    public static final String ACTIVIDAD = PAQUETE + "/.MainActivity";
    public static final String APK = "DomiRuta-Lector.apk";
    private static final int MAXIMO_INTENTOS_INSTALAR = 12;

    /** En que va el celular. */
    public enum Estado {
        SIN_ADB("No se encontraron las herramientas de Android (adb)"),
        BUSCANDO("Conecte el celular por cable USB"),
        SIN_PERMISO("Acepte en el celular el aviso «¿Permitir depuración USB?»"),
        PREGUNTANDO("Se conectó un celular: responda en el computador si lo va a usar como lector"),
        INSTALANDO("Instalando la app en el celular; si pregunta, toque «Instalar»"),
        SIN_APP("No se pudo instalar la app: instale DomiRuta-Lector.apk en el celular"),
        LISTO("Celular conectado: ya puede escanear"),
        ERROR("No se pudo hablar con el celular: desconecte y vuelva a conectar el cable");

        private final String texto;

        Estado(String texto) {
            this.texto = texto;
        }

        public String getTexto() {
            return texto;
        }
    }

    private final int puerto;
    private final Consumer<Estado> alCambiar;
    private final java.util.function.BiConsumer<String, String> alPreguntar; // (serie, nombre del celular)
    private final File archivoDecisiones;
    private final Map<String, Boolean> decisiones; // serie -> se usa como lector o no
    private final Set<String> preguntando = new HashSet<>();
    private final Set<String> abiertos = new HashSet<>();
    private final Map<String, Integer> intentos = new HashMap<>();
    private ScheduledExecutorService reloj;
    private volatile String adb;
    private volatile Estado estado = Estado.BUSCANDO;

    /**
     * @param alPreguntar se llama una vez por celular nuevo; la respuesta llega con {@link #decidir}
     */
    public PuenteCelular(int puerto, Consumer<Estado> alCambiar, java.util.function.BiConsumer<String, String> alPreguntar) {
        this.puerto = puerto;
        this.alCambiar = alCambiar;
        this.alPreguntar = alPreguntar;
        this.archivoDecisiones = new File(Ubicaciones.carpetaUsuario(), "celulares_lector.txt");
        this.decisiones = leerDecisiones(archivoDecisiones);
    }

    /** La respuesta a "¿usar este celular como lector?". Se guarda para no volver a preguntar. */
    public void decidir(String serie, boolean usar) {
        ScheduledExecutorService r = reloj;
        Runnable hacer = () -> {
            decisiones.put(serie, usar);
            preguntando.remove(serie);
            guardarDecisiones(archivoDecisiones, decisiones);
            revisar(); // enseguida, sin esperar los 5 segundos
        };
        if (r != null) {
            r.execute(hacer);
        }
    }

    /** Olvida las respuestas: la proxima vez que se conecte cada celular se vuelve a preguntar. */
    public void olvidarCelulares() {
        ScheduledExecutorService r = reloj;
        if (r != null) {
            r.execute(() -> {
                decisiones.clear();
                preguntando.clear();
                guardarDecisiones(archivoDecisiones, decisiones);
            });
        }
    }

    public synchronized void empezar() {
        if (reloj != null) {
            return;
        }
        reloj = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lector-celular");
            t.setDaemon(true);
            return t;
        });
        reloj.scheduleWithFixedDelay(this::revisar, 0, 5, TimeUnit.SECONDS);
    }

    public synchronized void parar() {
        if (reloj != null) {
            reloj.shutdownNow();
            reloj = null;
        }
    }

    public Estado getEstado() {
        return estado;
    }

    // ---------------- cada 5 segundos ----------------

    private void revisar() {
        Estado nuevo;
        try {
            if (adb == null) {
                adb = buscar(candidatosAdb());
            }
            if (adb == null) {
                nuevo = Estado.SIN_ADB;
            } else {
                Dispositivos d = leerDispositivos(correr(adb, "devices"));
                abiertos.retainAll(d.listos);
                intentos.keySet().retainAll(d.listos);
                if (d.listos.isEmpty()) {
                    nuevo = d.sinPermiso ? Estado.SIN_PERMISO : Estado.BUSCANDO;
                } else {
                    boolean faltaApp = false;
                    boolean hayLector = false;
                    boolean pendiente = false;
                    for (String serie : d.listos) {
                        Boolean usar = decisiones.get(serie);
                        if (usar == null) { // celular nuevo: se pregunta una vez y se espera la respuesta
                            pendiente = true;
                            if (preguntando.add(serie)) {
                                alPreguntar.accept(serie, nombreCelular(serie));
                            }
                            continue;
                        }
                        if (!usar) {
                            continue; // dijeron que solo esta cargando: no se le toca nada
                        }
                        hayLector = true;
                        correr(adb, "-s", serie, "reverse", "tcp:" + puerto, "tcp:" + puerto);
                        if (!abiertos.contains(serie) && !prepararApp(serie)) {
                            faltaApp = true;
                        }
                    }
                    nuevo = faltaApp ? Estado.SIN_APP : hayLector ? Estado.LISTO
                            : pendiente ? Estado.PREGUNTANDO : Estado.BUSCANDO;
                }
            }
        } catch (IOException e) {
            nuevo = Estado.ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        cambiar(nuevo);
    }

    /** Si el celular no tiene la app se la instala (si se encuentra el APK) y luego la abre. */
    private boolean prepararApp(String serie) throws IOException, InterruptedException {
        boolean instalada = correr(adb, "-s", serie, "shell", "pm", "path", PAQUETE).contains("package:");
        if (!instalada) {
            String apk = buscar(candidatosApk());
            if (apk == null) {
                abiertos.add(serie); // sin APK no hay que instalar: queda la pagina en Chrome
                return true;
            }
            int n = intentos.getOrDefault(serie, 0);
            if (n >= MAXIMO_INTENTOS_INSTALAR) {
                return false; // se vuelve a intentar cuando reconecten el cable
            }
            intentos.put(serie, n + 1);
            cambiar(Estado.INSTALANDO);
            instalada = correr(adb, "-s", serie, "install", "-r", apk).contains("Success");
            if (!instalada) {
                return false;
            }
        }
        correr(adb, "-s", serie, "shell", "am", "start", "-n", ACTIVIDAD);
        abiertos.add(serie);
        return true;
    }

    /** "Xiaomi 2312DRA50G": marca y modelo, para que la pregunta diga de cual celular se trata. */
    private String nombreCelular(String serie) {
        try {
            String marca = correr(adb, "-s", serie, "shell", "getprop", "ro.product.manufacturer").trim();
            String modelo = correr(adb, "-s", serie, "shell", "getprop", "ro.product.model").trim();
            String n = (marca.isEmpty() ? "" : Character.toUpperCase(marca.charAt(0)) + marca.substring(1) + " ") + modelo;
            return n.isBlank() || n.length() > 60 ? "Celular " + serie : n;
        } catch (IOException e) {
            return "Celular " + serie;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Celular " + serie;
        }
    }

    // ---------------- respuestas guardadas ----------------

    /** Lee "si serie" / "no serie", una por linea. */
    public static Map<String, Boolean> leerDecisiones(File f) {
        Map<String, Boolean> m = new java.util.concurrent.ConcurrentHashMap<>();
        try {
            if (f.isFile()) {
                for (String linea : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    String[] p = linea.trim().split("\\s+", 2);
                    if (p.length == 2 && (p[0].equals("si") || p[0].equals("no"))) {
                        m.put(p[1], p[0].equals("si"));
                    }
                }
            }
        } catch (IOException e) {
            Ubicaciones.registrarError(e); // sin el archivo se vuelve a preguntar: no se pierde nada grave
        }
        return m;
    }

    public static void guardarDecisiones(File f, Map<String, Boolean> m) {
        StringBuilder sb = new StringBuilder("# celulares que se usan (si) o no (no) como lector de codigos\n");
        for (Map.Entry<String, Boolean> e : m.entrySet()) {
            sb.append(e.getValue() ? "si " : "no ").append(e.getKey()).append('\n');
        }
        try {
            java.nio.file.Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Ubicaciones.registrarError(e);
        }
    }

    private void cambiar(Estado nuevo) {
        if (nuevo != estado) {
            estado = nuevo;
            alCambiar.accept(nuevo);
        }
    }

    // ---------------- lo que contesta adb ----------------

    /** Celulares que vio "adb devices". */
    public static final class Dispositivos {
        public final List<String> listos = new ArrayList<>();
        public boolean sinPermiso;
    }

    /**
     * Lee la salida de "adb devices":
     * <pre>
     * List of devices attached
     * 6054de3a	device
     * R58N12	unauthorized
     * </pre>
     */
    public static Dispositivos leerDispositivos(String salida) {
        Dispositivos d = new Dispositivos();
        for (String linea : salida.split("\\R")) {
            String[] p = linea.trim().split("\\s+");
            if (p.length != 2 || linea.startsWith("List") || linea.startsWith("*")) {
                continue;
            }
            if (p[1].equals("device")) {
                d.listos.add(p[0]);
            } else if (p[1].equals("unauthorized")) {
                d.sinPermiso = true;
            }
        }
        return d;
    }

    private static String correr(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] salida = p.getInputStream().readAllBytes(); // instalar puede tardar: espera a que termine
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        return new String(salida, StandardCharsets.UTF_8);
    }

    // ---------------- donde buscar adb y la app ----------------

    /**
     * adb se busca: junto a DomiRuta (carpeta lector), en el programa DomiRuta Lector instalado,
     * en el SDK de Android (Android Studio) y en el PATH.
     */
    static List<File> candidatosAdb() {
        String nombre = esWindows() ? "adb.exe" : "adb";
        List<File> l = new ArrayList<>();
        for (File base : carpetasLector()) {
            l.add(new File(base, "platform-tools" + File.separator + nombre));
        }
        String sdk = System.getenv("ANDROID_HOME");
        if (sdk != null) {
            l.add(new File(sdk, "platform-tools" + File.separator + nombre));
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            l.add(new File(local, "Android\\Sdk\\platform-tools\\adb.exe"));
        }
        l.add(new File("C:\\platform-tools\\adb.exe"));
        String casa = System.getProperty("user.home");
        l.add(new File(casa, "Android/Sdk/platform-tools/adb"));
        l.add(new File(casa, "Library/Android/sdk/platform-tools/adb"));
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                l.add(new File(dir, nombre));
            }
        }
        return l;
    }

    static List<File> candidatosApk() {
        List<File> l = new ArrayList<>();
        for (File base : carpetasLector()) {
            l.add(new File(base, APK));
        }
        return l;
    }

    /** Carpetas donde pueden estar adb y el APK: la de DomiRuta y la del programa DomiRuta Lector. */
    private static List<File> carpetasLector() {
        List<File> l = new ArrayList<>();
        File programa = carpetaPrograma();
        l.add(new File(programa, "lector"));
        l.add(new File(System.getProperty("user.dir"), "lector"));
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            l.add(new File(local, "Programs\\DomiRuta Lector\\app"));
        }
        return l;
    }

    private static File carpetaPrograma() {
        try {
            File f = new File(PuenteCelular.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() ? f.getParentFile() : f;
        } catch (Exception e) {
            return new File(System.getProperty("user.dir"));
        }
    }

    private static String buscar(List<File> candidatos) {
        for (File f : candidatos) {
            if (f.isFile()) {
                return f.getPath();
            }
        }
        return null;
    }

    private static boolean esWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
