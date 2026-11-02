package rutas.vista;

import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import rutas.logica.AjustesRecibo;

/**
 * Imprime en cualquier impresora que tenga su controlador en Windows: el programa dibuja el
 * recibo al ancho del papel configurado y Windows lo manda. Cortar el papel y abrir el cajon
 * van aparte como comandos ESC/POS (los entienden casi todas las termicas); si una no los
 * acepta, el recibo igual sale.
 */
final class Impresion {

    /** Abrir cajon: ESC p 0 25 250. */
    private static final byte[] CAJON = {27, 112, 0, 25, (byte) 250};
    /** Avanzar un poco y cortar: ESC d 3, GS V 66 0. */
    private static final byte[] CORTAR = {27, 100, 3, 29, 86, 66, 0};

    private Impresion() {
    }

    static double mmAPuntos(double mm) {
        return mm * 72 / 25.4;
    }

    /** Nombres de las impresoras instaladas. */
    static String[] impresoras() {
        PrintService[] ps = PrintServiceLookup.lookupPrintServices(null, null);
        String[] r = new String[ps.length];
        for (int i = 0; i < ps.length; i++) {
            r[i] = ps[i].getName();
        }
        return r;
    }

    static PrintService buscar(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return null;
        }
        for (PrintService ps : PrintServiceLookup.lookupPrintServices(null, null)) {
            if (ps.getName().equalsIgnoreCase(nombre)) {
                return ps;
            }
        }
        return null;
    }

    /** Pagina del alto justo del recibo y el ancho del rollo, con los margenes configurados. */
    static PageFormat pagina(PrinterJob job, AjustesRecibo a, double altoPuntos) {
        PageFormat pf = job.defaultPage();
        Paper papel = new Paper();
        double w = mmAPuntos(a.anchoMm);
        double h = Math.max(altoPuntos + mmAPuntos(8), mmAPuntos(40));
        papel.setSize(w, h);
        papel.setImageableArea(mmAPuntos(a.margenIzqMm), 0, mmAPuntos(a.anchoUtilMm()), h);
        pf.setPaper(papel);
        pf.setOrientation(PageFormat.PORTRAIT);
        return pf;
    }

    /** True si Windows tiene al menos una impresora instalada. */
    static boolean hayImpresoras() {
        return PrintServiceLookup.lookupPrintServices(null, null).length > 0;
    }

    /**
     * Prepara el trabajo (en la pantalla: puede mostrar el cuadro de Windows para escoger impresora).
     * Devuelve null si la persona cancelo. Lanza IllegalStateException si no hay impresoras.
     */
    static PrinterJob preparar(Printable p, double altoPuntos, AjustesRecibo a, String trabajo) {
        if (!hayImpresoras()) {
            throw new IllegalStateException("No hay ninguna impresora instalada en este computador.");
        }
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName(trabajo);
        PrintService ps = buscar(a.impresora);
        try {
            if (ps != null) {
                job.setPrintService(ps);
            }
        } catch (PrinterException e) {
            ps = null;
        }
        job.setPrintable(p, pagina(job, a, altoPuntos));
        job.setCopies(a.copias);
        if (ps == null && !job.printDialog()) {
            return null;
        }
        return job;
    }

    /** Imprime (puede tardar: se llama fuera de la pantalla). Devuelve un aviso o null si todo salio bien. */
    static String terminar(PrinterJob job, AjustesRecibo a) throws PrinterException {
        job.print();
        PrintService ps = buscar(a.impresora);
        String aviso = !a.impresora.isBlank() && ps == null
                ? "No se encontró la impresora «" + a.impresora + "»: revise Configuración › Impresora." : null;
        if (a.abrirCajon || a.cortar) {
            byte[] cmd = a.cortar && a.abrirCajon ? junto(CORTAR, CAJON) : a.cortar ? CORTAR : CAJON;
            if (!enviarCrudo(job.getPrintService(), cmd)) {
                aviso = "El recibo salió, pero esta impresora no aceptó la orden de cortar o abrir el cajón."
                        + " Puede apagar esas opciones en Configuración › Impresora.";
            }
        }
        return aviso;
    }

    /**
     * Manda a imprimir de una vez (preparar + terminar). Devuelve un aviso para el usuario o null si todo salio bien.
     */
    static String imprimir(Printable p, double altoPuntos, AjustesRecibo a, String trabajo) throws PrinterException {
        PrinterJob job;
        try {
            job = preparar(p, altoPuntos, a, trabajo);
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        return job == null ? "No se imprimió" : terminar(job, a);
    }

    /**
     * Imprime sin congelar el programa: prepara en pantalla y manda el trabajo aparte. Si en 15 segundos
     * la impresora no ha respondido, avisa (el trabajo sigue en la cola de Windows).
     * alTerminar recibe null si salio bien, o el aviso para mostrar.
     */
    static void imprimirAparte(Printable p, double altoPuntos, AjustesRecibo a, String trabajo,
            java.util.function.Consumer<String> alTerminar) {
        PrinterJob job;
        try {
            job = preparar(p, altoPuntos, a, trabajo);
        } catch (IllegalStateException e) {
            alTerminar.accept(e.getMessage() + " Puede guardar el recibo en PDF o enviarlo por correo.");
            return;
        }
        if (job == null) {
            alTerminar.accept("No se imprimió");
            return;
        }
        boolean[] avisado = {false};
        javax.swing.Timer espera = new javax.swing.Timer(15_000, e -> {
            avisado[0] = true;
            alTerminar.accept("La impresora no responde. Revise que esté prendida, conectada y con papel."
                    + " El recibo quedó en la cola de impresión de Windows.");
        });
        espera.setRepeats(false);
        espera.start();
        new javax.swing.SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    return terminar(job, a);
                } catch (PrinterException | RuntimeException e) {
                    return "No se pudo imprimir: " + e.getMessage();
                }
            }

            @Override
            protected void done() {
                espera.stop();
                if (avisado[0]) {
                    return; // ya se le dijo a la persona; no se repite
                }
                String r;
                try {
                    r = get();
                } catch (Exception e) {
                    r = "No se pudo imprimir: " + e.getMessage();
                }
                alTerminar.accept(r);
            }
        }.execute();
    }

    private static byte[] junto(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** Manda bytes tal cual (ESC/POS). False si la impresora no acepta datos crudos. */
    static boolean enviarCrudo(PrintService ps, byte[] datos) {
        if (ps == null) {
            return false;
        }
        DocFlavor sabor = DocFlavor.BYTE_ARRAY.AUTOSENSE;
        if (!ps.isDocFlavorSupported(sabor)) {
            return false;
        }
        try {
            DocPrintJob dj = ps.createPrintJob();
            dj.print(new SimpleDoc(datos, sabor, null), null);
            return true;
        } catch (Exception e) {
            rutas.logica.Ubicaciones.registrarError(e);
            return false;
        }
    }
}
