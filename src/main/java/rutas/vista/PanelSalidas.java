package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import rutas.logica.NubeRepartidor;
import rutas.logica.Tarifa;
import rutas.modelo.Parada;
import rutas.modelo.Ruta;
import rutas.modelo.Salida;

/**
 * Apartado Salidas: lo que va con el repartidor. Cada salida muestra sus entregas con el estado
 * que él marca desde el celular (o que se marca aquí), por dónde va, los mensajes y el botón
 * «Recibí» para cuadrar la plata (si no debe nada se cierra sola). Las que no se entregaron vuelven a las pendientes.
 */
public class PanelSalidas extends JPanel {

    private final Aplicacion app;
    private final JPanel lista = new JPanel();
    private Seguimiento seguimiento;

    public PanelSalidas(Aplicacion app) {
        this.app = app;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 20, 16));
        add(Tema.tituloPantalla("Salidas del repartidor"));
        add(Tema.parrafo("Lo que va en camino. El domiciliario marca cada entrega desde el enlace que le llega por "
                + "WhatsApp. Si al terminar no debe nada, la salida se cierra sola; si trae plata, oprima «Recibí».", 272));
        add(Tema.espacio(12));
        lista.setOpaque(false);
        lista.setLayout(new BoxLayout(lista, BoxLayout.Y_AXIS));
        lista.setAlignmentX(LEFT_ALIGNMENT);
        add(lista);
        app.alCambiar(this::refrescar);
        refrescar();
        // cada minuto, por si alguien no responde (pasa a «no ha respondido» y sale «Escoger a otro»)
        new javax.swing.Timer(60_000, e -> {
            for (Salida s : app.salidasAbiertas()) {
                if (s.esperandoRespuesta()) {
                    refrescar();
                    return;
                }
            }
        }).start();
    }

    private final java.util.Map<Long, JComponent> tarjetas = new java.util.HashMap<>(); // id de salida -> tarjeta

    /** Baja hasta la tarjeta de esa salida (desde la campana de avisos). */
    void mostrarSalida(long id) {
        JComponent t = tarjetas.get(id);
        if (t != null) {
            t.scrollRectToVisible(new java.awt.Rectangle(0, 0, t.getWidth(), Math.min(t.getHeight(), 400)));
        }
    }

    void setSeguimiento(Seguimiento s) {
        this.seguimiento = s;
    }

    // ------------------------------------------------------------------

    void refrescar() {
        lista.removeAll();
        tarjetas.clear();
        lista.add(tarjetaPendientes());
        List<Salida> abiertas = app.salidasAbiertas();
        List<Salida> cerradas = new ArrayList<>();
        for (Salida s : app.getSalidas()) {
            if (!s.abierta() && s.cerrada != null && s.cerrada.toLocalDate().equals(java.time.LocalDate.now())) {
                cerradas.add(s);
            }
        }
        for (int i = abiertas.size() - 1; i >= 0; i--) { // la más nueva arriba
            lista.add(Tema.espacio(10));
            JComponent tj = tarjetaSalida(abiertas.get(i));
            tarjetas.put(abiertas.get(i).id, tj);
            lista.add(tj);
        }
        if (abiertas.isEmpty()) {
            lista.add(Tema.espacio(10));
            Tema.Tarjeta t = new Tema.Tarjeta();
            t.add(Tema.textoNormal("No hay salidas en camino."));
            t.add(Tema.parrafo("Calcule la ruta en «Ruta» y oprima «Enviar por WhatsApp»: las entregas pasan aquí.", 260));
            lista.add(t);
        }
        if (!cerradas.isEmpty()) {
            lista.add(Tema.espacio(10));
            lista.add(tarjetaCerradas(cerradas));
        }
        lista.revalidate();
        lista.repaint();
    }

    private JComponent tarjetaPendientes() {
        Tema.Tarjeta t = new Tema.Tarjeta();
        int n = app.getEntregas().size();
        JLabel l = Tema.textoNormal(n == 0 ? "No hay entregas esperando salir"
                : n == 1 ? "1 entrega esperando salir" : n + " entregas esperando salir");
        l.setFont(Tema.textoFuerte(14));
        t.add(l);
        NubeRepartidor.Ajustes a = app.getAjustesNube();
        String estado;
        Color color;
        if (a == null || !a.listo()) {
            estado = "Sin conexión configurada: el domiciliario no recibe el enlace. Configúrela en Configuración › Conexión.";
            color = Tema.AVISO_TEXTO;
        } else if (seguimiento != null && !seguimiento.getError().isEmpty()) {
            estado = seguimiento.getError();
            color = Tema.ERROR_TEXTO;
        } else {
            estado = "Conectado: lo que marque el domiciliario aparece aquí solo.";
            color = Tema.EXITO_TEXTO;
        }
        Tema.Parrafo p = Tema.parrafo(estado, 272);
        p.setForeground(color);
        t.add(Tema.espacio(4));
        t.add(p);
        if (n > 0) {
            Tema.Boton ir = Tema.botonPrimario("Despachar");
            ir.setToolTipText("Calcula la ruta, pregunta quién la lleva y le manda la ruta por WhatsApp");
            ir.addActionListener(e -> {
                if (app.getVentana() != null) {
                    app.getVentana().irARuta();
                    app.getVentana().getPedidos().despacharTodo();
                }
            });
            Tema.Boton preguntar = Tema.botonSecundario("Preguntar quién puede");
            preguntar.setIcon(new CapaCobro.LogoPago("/imagenes/archivos/whatsapp.png", 18));
            preguntar.setIconTextGap(8);
            preguntar.setToolTipText("Abre WhatsApp con «Hay domicilio…» para mandarlo al grupo o a los independientes");
            preguntar.addActionListener(e -> preguntarQuienPuede());
            t.add(Tema.espacio(8));
            t.add(Tema.fila(8, ir, preguntar));
        }
        if (app.getVentana() != null && app.getVentana().puedeEditarDomiciliarios()) {
            Tema.Boton lista = Tema.botonPequeno("Domiciliarios");
            lista.setToolTipText("La lista de quienes llevan los domicilios: nombre, celular, tipo");
            lista.addActionListener(e -> app.getVentana().irADomiciliarios());
            t.add(Tema.espacio(8));
            t.add(lista);
        }
        return t;
    }

    /** «Debe entregar $45.000 · devolver Venta N.º 00123» (para la tarjeta y el aviso). */
    static String loQueDebe(Salida s) {
        List<String> l = new ArrayList<>();
        int d = s.debeEntregar();
        if (d > 0) {
            l.add("Debe entregar " + Tarifa.pesos(d));
        } else if (d < 0) {
            l.add("Usted le debe " + Tarifa.pesos(-d));
        }
        List<Salida.Entrega> b = s.bolsasPorDevolver();
        if (!b.isEmpty()) {
            StringBuilder sb = new StringBuilder(l.isEmpty() ? "Debe devolver " : "devolver ");
            for (int i = 0; i < b.size(); i++) {
                Parada p = b.get(i).parada;
                sb.append(i == 0 ? "" : ", ").append(p.getVentaNumero() > 0
                        ? "Venta N.º " + String.format("%05d", p.getVentaNumero()) : "la bolsa de " + p.getNombre());
            }
            l.add(sb.toString());
        }
        return l.isEmpty() ? "Entregó todo; no debe nada" : String.join(" · ", l);
    }

    private static String hora(LocalDateTime t) {
        return Ruta.FORMATO_HORA.format(t);
    }

    private JComponent tarjetaSalida(Salida s) {
        Tema.Tarjeta t = new Tema.Tarjeta();
        JPanel cab = new JPanel(new BorderLayout(8, 0));
        cab.setOpaque(false);
        cab.setAlignmentX(LEFT_ALIGNMENT);
        JLabel tit = new JLabel("Salida " + hora(s.creada) + (s.repartidor.isEmpty() ? "" : " · " + s.repartidor));
        tit.setFont(Tema.textoFuerte(14.5f));
        tit.setForeground(Tema.AZUL_PROFUNDO);
        boolean sinNadie = s.enLinea() && s.rechazadaEn != null;
        boolean esperando = s.esperandoRespuesta() && !s.subiendo;
        boolean termino = s.estado == Salida.Estado.REGRESANDO;
        boolean vencida = esperando && s.vencida();
        JLabel est = new JLabel(sinNadie ? "Sin domiciliario" : vencida ? "Venció" : esperando ? "Esperando"
                : termino ? "Por cuadrar" : s.estado.toString());
        est.setFont(Tema.textoFuerte(13.5f));
        est.setForeground(sinNadie || vencida ? Tema.ERROR_TEXTO : esperando || s.estado == Salida.Estado.REGRESANDO
                ? Tema.AVISO_TEXTO : Tema.AZUL);
        cab.add(tit, BorderLayout.CENTER);
        cab.add(est, BorderLayout.EAST);
        cab.setMaximumSize(new Dimension(Integer.MAX_VALUE, cab.getPreferredSize().height));
        t.add(cab);
        t.add(Tema.espacio(4));
        int entregadas = s.cuantas(Salida.Entrega.ENTREGADO);
        JLabel res = Tema.textoNormal(entregadas + " de " + s.entregas.size() + " entregadas"
                + (termino ? "" : " · Plata a traer " + Tarifa.pesos(s.debeEntregar()))); // al terminar va abajo
        res.setFont(Tema.texto(13.5f));
        t.add(Tema.anchoLibre(res));
        String quien = s.repartidor.isEmpty() ? "el domiciliario" : PanelPedidos.primerNombre(s.repartidor);
        boolean otro = false; // mostrar «Escoger a otro»
        if (s.enLinea() && s.rechazadaEn != null) {
            Tema.Parrafo p = Tema.parrafo(quien + " no puede llevarla" + (s.motivoRechazo.isEmpty() ? "." : ": "
                    + s.motivoRechazo + ".") + " Escoja a otro.", 260);
            p.setFont(Tema.textoFuerte(13));
            p.setForeground(Tema.ERROR_TEXTO);
            t.add(p);
            otro = true;
        } else if (vencida) {
            Tema.Parrafo p = Tema.parrafo(quien + " no respondió antes de las " + hora(s.venceEn)
                    + " y el enlace ya no deja aceptarla: escoja a otro.", 260);
            p.setFont(Tema.textoFuerte(13));
            p.setForeground(Tema.ERROR_TEXTO);
            t.add(p);
            otro = true;
        } else if (s.esperandoRespuesta() && !s.subiendo) {
            long min = s.enviadaEn == null ? 0 : java.time.Duration.between(s.enviadaEn, LocalDateTime.now()).toMinutes();
            String antes = s.venceEn == null ? "" : " Tiene hasta las " + hora(s.venceEn) + " para aceptar.";
            Tema.Parrafo p = Tema.parrafo((min >= Seguimiento.MIN_SIN_RESPUESTA ? quien + " no ha respondido (" + min + " min)."
                    : "Esperando que " + quien + " acepte…") + antes, 260);
            p.setFont(Tema.textoFuerte(13));
            p.setForeground(Tema.AVISO_TEXTO);
            t.add(p);
            otro = min >= Seguimiento.MIN_SIN_RESPUESTA;
        } else if (s.aceptadaEn != null) {
            JLabel a = Tema.textoNormal(quien + " aceptó · " + hora(s.aceptadaEn));
            a.setFont(Tema.textoFuerte(13));
            a.setForeground(Tema.EXITO_TEXTO);
            t.add(a);
            if (s.estado == Salida.Estado.EN_CAMINO && s.ubicacionPausada) {
                JLabel u = Tema.textoNormal("Pausó su ubicación");
                u.setFont(Tema.textoFuerte(13));
                u.setForeground(Tema.AVISO_TEXTO);
                t.add(u);
            } else if (s.estado == Salida.Estado.EN_CAMINO && s.avisos.contains("sin_ubicacion")) {
                JLabel u = Tema.textoNormal("Sin ubicación hace más de " + Seguimiento.MIN_SIN_UBICACION + " min");
                u.setFont(Tema.textoFuerte(13));
                u.setForeground(Tema.AVISO_TEXTO);
                t.add(u);
            }
        }
        if (s.ubicacionEn != null) {
            JLabel u = Tema.textoNormal("Ubicación de las " + hora(s.ubicacionEn));
            u.setFont(Tema.texto(13));
            u.setForeground(Tema.GRIS_TEXTO);
            t.add(u);
        } else if (s.subiendo) {
            Tema.Parrafo p = Tema.parrafo("Preparando el enlace del repartidor…", 260);
            p.setForeground(Tema.GRIS_TEXTO);
            t.add(p);
        } else if (!s.enLinea()) {
            Tema.Parrafo p = Tema.parrafo("El domiciliario no tiene el enlace de esta salida."
                    + (s.errorNube.isEmpty() ? "" : " Motivo: " + s.errorNube), 260);
            p.setForeground(Tema.AVISO_TEXTO);
            t.add(p);
        }
        t.add(Tema.espacio(8));
        for (int i = 0; i < s.entregas.size(); i++) {
            t.add(filaEntrega(s, s.entregas.get(i), i + 1));
        }
        if (termino) { // ya marcó todas: lo único que falta es la plata o las bolsas
            t.add(Tema.espacio(8));
            Tema.Parrafo debe = Tema.parrafo(loQueDebe(s), 300);
            debe.setFont(Tema.textoFuerte(14));
            debe.setForeground(Tema.AZUL_PROFUNDO);
            t.add(debe);
        }
        t.add(Tema.espacio(10));
        // terminó: un clic («Recibí $X»); sin terminar (o sin enlace): cerrar a mano marcando lo entregado
        Tema.Boton volvio;
        if (termino) {
            int d = s.debeEntregar();
            int bolsas = s.bolsasPorDevolver().size();
            volvio = Tema.botonPrimario(d > 0 ? "Recibí " + Tarifa.pesos(d) : d < 0 ? "Le pagué " + Tarifa.pesos(-d)
                    : bolsas == 1 ? "Devolvió la bolsa" : bolsas > 1 ? "Devolvió las bolsas" : "Cerrar ya");
            volvio.setToolTipText("Cerrar la salida: queda en el historial"
                    + (bolsas > 0 ? " y lo no entregado vuelve a pendientes" : ""));
            volvio.addActionListener(e -> cerrarSalida(s, new HashSet<>(s.bolsasPorDevolver())));
        } else {
            volvio = Tema.botonSecundario("Cerrar a mano");
            volvio.setToolTipText("Marcar aquí lo que entregó, cuadrar la plata y cerrar la salida");
            volvio.addActionListener(e -> volvio(s));
        }
        volvio.setEnabled(!s.subiendo);
        int sinLeer = s.mensajesSinLeer();
        Tema.Boton msj = Tema.botonSecundario(sinLeer > 0 ? "Mensajes (" + sinLeer + ")" : "Mensajes");
        msj.setEnabled(s.enLinea());
        msj.setToolTipText(s.enLinea() ? "Escribirle al repartidor" : "Esta salida no está en internet");
        msj.addActionListener(e -> mensajes(s));
        List<JComponent> botones = new ArrayList<>(List.of(volvio, msj));
        if (termino) {
            Tema.Boton corregir = Tema.botonPequeno("Corregir");
            corregir.setToolTipText("Cambiar lo entregado antes de cerrar");
            corregir.addActionListener(e -> volvio(s));
            botones.add(corregir);
        }
        if (!s.enLinea() && !s.subiendo) {
            Tema.Boton crear = Tema.botonPequeno("Enviar enlace");
            crear.setToolTipText("Escoja quién la lleva: le llega por WhatsApp el enlace para marcar las entregas");
            crear.addActionListener(e -> ElegirDomiciliario.abrir(this, app, s.entregas.size() + " entregas",
                    d -> crearEnlace(s, d, crear)));
            botones.add(0, crear);
            botones.remove(msj); // sin enlace no hay mensajes
        }
        if (otro) {
            Tema.Boton cambiar = Tema.botonPrimario("Escoger a otro");
            cambiar.setToolTipText("Le llega un enlace nuevo al que escoja; el anterior deja de servir");
            cambiar.addActionListener(e -> ElegirDomiciliario.abrir(this, app, s.entregas.size() + " entregas",
                    d -> crearEnlace(s, d, cambiar)));
            botones.add(0, cambiar);
            volvio.setVisible(false); // todavía no ha salido nadie con ella
        }
        if (s.ubicacionEn != null && app.getPanelMapa() != null) {
            Tema.Boton mapa = Tema.botonPequeno("Ver en el mapa");
            mapa.addActionListener(e -> app.getPanelMapa().verRepartidor(s));
            botones.add(mapa);
        }
        if (!app.getEntregas().isEmpty() && !s.subiendo) {
            Tema.Boton agregar = Tema.botonPequeno("Agregar entrega");
            agregar.setToolTipText("Mandar una entrega pendiente en esta salida, sin que el repartidor vuelva");
            agregar.addActionListener(e -> agregarEntrega(s));
            botones.add(agregar);
        }
        t.add(Tema.fila(8, botones.toArray(new JComponent[0])));
        return t;
    }

    private JComponent filaEntrega(Salida s, Salida.Entrega e, int n) {
        // arriba el nombre con el estado a la derecha; abajo la dirección con todo el ancho
        JPanel f = new JPanel();
        f.setLayout(new BoxLayout(f, BoxLayout.Y_AXIS));
        f.setOpaque(false);
        f.setAlignmentX(LEFT_ALIGNMENT);
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Tema.BORDE),
                BorderFactory.createEmptyBorder(6, 0, 6, 0)));
        JPanel arriba = new JPanel(new BorderLayout(8, 0));
        arriba.setOpaque(false);
        arriba.setAlignmentX(LEFT_ALIGNMENT);
        JLabel nom = new JLabel(n + ". " + e.parada.getNombre());
        nom.setFont(Tema.textoFuerte(13.5f));
        nom.setForeground(Tema.GRIS_OSCURO);
        nom.setMinimumSize(new Dimension(40, nom.getPreferredSize().height));
        JLabel est = new JLabel(e.textoEstado() + (e.tieneFoto ? " · foto" : ""));
        est.setFont(Tema.textoFuerte(12.5f));
        est.setForeground(e.entregada() ? Tema.EXITO_TEXTO : e.noEntregada() ? Tema.ERROR_TEXTO : Tema.AZUL);
        arriba.add(nom, BorderLayout.CENTER);
        arriba.add(est, BorderLayout.EAST);
        arriba.setMaximumSize(new Dimension(Integer.MAX_VALUE, arriba.getPreferredSize().height));
        f.add(arriba);
        Tema.Parrafo dir = Tema.parrafo(e.parada.getDireccion() + (e.cobrar > 0 ? " · Cobrar " + Tarifa.pesos(e.cobrar)
                : " · Ya pagó"), 330);
        f.add(dir);
        f.setToolTipText("Clic para marcar a mano o ver la foto");
        f.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        java.awt.event.MouseAdapter clic = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent ev) {
                java.awt.Point p = javax.swing.SwingUtilities.convertPoint(ev.getComponent(), ev.getPoint(), f);
                menuEntrega(s, e, f, p.x, p.y);
            }
        };
        f.addMouseListener(clic);
        dir.addMouseListener(clic); // el texto de la dirección no deja pasar el clic
        dir.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return f;
    }

    /** WhatsApp al cliente con su enlace y su código (en su chat si el celular es válido). */
    private void enviarSeguimiento(Salida.Entrega e) {
        NubeRepartidor.Ajustes aj = app.getAjustesNube();
        if (aj == null) {
            return;
        }
        String msj = rutas.logica.MensajesWhatsApp.seguimiento(app.getNegocio(), e.parada.getNombre(),
                NubeRepartidor.enlaceCliente(aj.pagina, e.enlaceCliente), NubeRepartidor.enlaceCliente(aj.pagina, ""),
                e.codigoCliente);
        String cel = rutas.logica.Avisos.numeroWhatsApp(e.parada.getTelefono()).isEmpty() ? "" : e.parada.getTelefono();
        try {
            AbrirWhatsApp.abrir(cel, msj);
        } catch (Exception ex) {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(msj), null);
            Aviso.mostrar(this, Aviso.Tipo.AVISO, "Mensaje copiado", "No se abrió WhatsApp: péguelo en el chat del cliente");
        }
    }

    private void menuEntrega(Salida s, Salida.Entrega e, Component donde, int x, int y) {
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem ok = new javax.swing.JMenuItem("Marcar entregado");
        javax.swing.JMenuItem no = new javax.swing.JMenuItem("Marcar no entregado");
        javax.swing.JMenuItem camino = new javax.swing.JMenuItem("Volver a «en camino»");
        ok.addActionListener(a -> marcar(s, e, Salida.Entrega.ENTREGADO, ""));
        no.addActionListener(a -> marcar(s, e, Salida.Entrega.NO_ENTREGADO, "Marcado en la tienda"));
        camino.addActionListener(a -> marcar(s, e, Salida.Entrega.EN_CAMINO, ""));
        m.add(ok);
        m.add(no);
        m.add(camino);
        if (s.enLinea() && e.enCamino() && !e.enlaceCliente.isEmpty()) {
            javax.swing.JMenuItem seg = new javax.swing.JMenuItem("Enviar seguimiento al cliente");
            seg.setToolTipText("Le llega por WhatsApp su enlace para ver por dónde va el pedido");
            seg.addActionListener(a -> enviarSeguimiento(e));
            m.addSeparator();
            m.add(seg);
        }
        if (e.tieneFoto || !e.foto.isEmpty()) {
            javax.swing.JMenuItem foto = new javax.swing.JMenuItem("Ver foto de entrega");
            foto.addActionListener(a -> verFoto(s, e));
            m.addSeparator();
            m.add(foto);
        }
        m.show(donde, x, y);
    }

    /**
     * La tienda marca a mano (por ejemplo si el repartidor avisó llamando). Se pasa a internet en la
     * revisión (que se pide ya); mientras no llegue, lo que diga internet no la cambia.
     */
    private void marcar(Salida s, Salida.Entrega e, String estado, String motivo) {
        e.estado = estado;
        e.motivo = motivo;
        e.marcadaEn = System.currentTimeMillis();
        e.marcaPendiente = s.enLinea() || s.subiendo;
        s.revisarEstado();
        try {
            app.guardarSalida(s);
        } catch (IOException ex) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "Salidas", ex.getMessage());
        }
        if (app.cerrarSiTermino(s)) {
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Salida cerrada", "Todo entregado y no debe nada");
        }
        app.avisar();
        app.revisarYa();
    }

    // ------------------------------------------------------------------
    //  Cerrar a mano: cuadre y cierre
    // ------------------------------------------------------------------

    private void volvio(Salida s) {
        JPanel t = Capa.tarjeta(520, 24, 28);
        JLabel titulo = new JLabel("Cerrar la salida" + (s.repartidor.isEmpty() ? "" : " de " + s.repartidor));
        titulo.setFont(Tema.subtitulo(18));
        titulo.setForeground(Tema.AZUL_PROFUNDO);
        titulo.setAlignmentX(LEFT_ALIGNMENT);
        t.add(titulo);
        t.add(Tema.espacio(2));
        Tema.Parrafo sub = Tema.parrafo("Desmarque las que no entregó: vuelven a las pendientes para la próxima salida.", 460);
        sub.setFont(Tema.texto(13.5f));
        t.add(sub);
        t.add(Tema.espacio(10));
        List<JCheckBox> casillas = new ArrayList<>();
        List<Salida.Entrega> vistas = new ArrayList<>(s.entregas); // las que se ven en este cuadro
        JLabel total = new JLabel();
        total.setFont(Tema.titulo(20));
        total.setForeground(Tema.AZUL_PROFUNDO);
        total.setAlignmentX(LEFT_ALIGNMENT);
        JLabel detalle = new JLabel(" ");
        detalle.setFont(Tema.texto(13.5f));
        detalle.setForeground(Tema.GRIS_TEXTO);
        detalle.setAlignmentX(LEFT_ALIGNMENT);
        Runnable sumar = () -> {
            int plata = 0, domicilios = 0;
            for (int i = 0; i < casillas.size(); i++) {
                if (casillas.get(i).isSelected()) {
                    plata += vistas.get(i).cobrar;
                    domicilios += vistas.get(i).domicilio;
                }
            }
            if (s.seQuedaConDomicilios()) { // el independiente cobra todo y se queda con los domicilios
                int neto = plata - domicilios;
                detalle.setText("Cobró " + Tarifa.pesos(plata) + " · domicilios " + Tarifa.pesos(domicilios) + " (son de él)");
                total.setText(neto >= 0 ? "Le entrega " + Tarifa.pesos(neto) : "Usted le paga " + Tarifa.pesos(-neto));
            } else {
                detalle.setText("Lo que cobró en las puertas");
                total.setText("Debe traer " + Tarifa.pesos(plata));
            }
        };
        for (Salida.Entrega e : vistas) {
            JCheckBox c = Tema.casilla(e.parada.getNombre() + " · " + (e.cobrar > 0 ? Tarifa.pesos(e.cobrar) : "ya pagó")
                    + (e.noEntregada() ? " (" + e.textoEstado() + ")" : ""));
            c.setSelected(!e.noEntregada());
            c.addActionListener(a -> sumar.run());
            casillas.add(c);
            t.add(c);
        }
        sumar.run();
        t.add(Tema.espacio(10));
        t.add(detalle);
        t.add(total);
        t.add(Tema.espacio(14));
        Tema.Boton cerrar = Tema.botonPrimario("Cerrar salida");
        Tema.Boton cancelar = Tema.botonSecundario("Cancelar");
        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        pie.setAlignmentX(LEFT_ALIGNMENT);
        pie.add(Tema.fila(8, cancelar, cerrar), BorderLayout.EAST);
        t.add(pie);
        Capa capa = new Capa(capaDentro(), t, true);
        cancelar.addActionListener(a -> capa.cerrar());
        cerrar.addActionListener(a -> {
            Set<Salida.Entrega> no = new HashSet<>();
            for (int i = 0; i < casillas.size(); i++) {
                if (!casillas.get(i).isSelected()) {
                    no.add(vistas.get(i));
                }
            }
            for (Salida.Entrega e : s.entregas) {
                if (!vistas.contains(e)) {
                    no.add(e); // se agregó con este cuadro abierto: no se vio, así que vuelve a pendientes
                }
            }
            capa.cerrar();
            cerrarSalida(s, no);
        });
        capa.mostrar(cerrar);
    }

    private void cerrarSalida(Salida s, Set<Salida.Entrega> noEntregadas) {
        if (!s.abierta()) { // mientras estaba el cuadro abierto se cerró sola (no debía nada)
            Aviso.mostrar(this, Aviso.Tipo.AVISO, "Salida ya cerrada", "Se cerró sola: entregó todo y no debía nada");
            return;
        }
        try {
            app.cerrarSalida(s, noEntregadas);
        } catch (IOException ex) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "Salidas", ex.getMessage());
            return;
        }
        Aviso.mostrar(this, Aviso.Tipo.BIEN, "Salida cerrada", noEntregadas.isEmpty() ? "Todo entregado"
                : noEntregadas.size() == 1 ? "1 entrega vuelve a pendientes" : noEntregadas.size() + " entregas vuelven a pendientes");
        // la revisión la cierra en internet, baja las fotos y la borra (si no hay internet, lo reintenta)
        app.revisarYa();
    }

    // ------------------------------------------------------------------
    //  Agregar una entrega pendiente a la salida en camino
    // ------------------------------------------------------------------

    private void agregarEntrega(Salida s) {
        List<Parada> pend = new ArrayList<>(app.getEntregas());
        if (pend.isEmpty()) {
            return;
        }
        JPanel t = Capa.tarjeta(520, 24, 28);
        JLabel titulo = new JLabel("Agregar a la salida de las " + hora(s.creada));
        titulo.setFont(Tema.subtitulo(18));
        titulo.setForeground(Tema.AZUL_PROFUNDO);
        titulo.setAlignmentX(LEFT_ALIGNMENT);
        t.add(titulo);
        t.add(Tema.espacio(2));
        Tema.Parrafo sub = Tema.parrafo(s.enLinea() ? "Al repartidor le aparece en el celular en menos de 30 segundos."
                : "Esta salida no está en internet: avísele por WhatsApp o llamando.", 460);
        sub.setFont(Tema.texto(13.5f));
        t.add(sub);
        t.add(Tema.espacio(10));
        javax.swing.DefaultListModel<Parada> modelo = new javax.swing.DefaultListModel<>();
        pend.forEach(modelo::addElement);
        javax.swing.JList<Parada> l = new Tema.Lista<>(modelo, "");
        l.setSelectedIndex(0);
        l.setFont(Tema.texto(14));
        JScrollPane sc = new JScrollPane(l);
        sc.setBorder(Tema.bordeLista());
        sc.setAlignmentX(LEFT_ALIGNMENT);
        sc.setPreferredSize(new Dimension(460, Math.min(5, pend.size()) * 28 + 8));
        t.add(sc);
        t.add(Tema.espacio(14));
        Tema.Boton ok = Tema.botonPrimario("Agregar");
        Tema.Boton cancelar = Tema.botonSecundario("Cancelar");
        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        pie.setAlignmentX(LEFT_ALIGNMENT);
        pie.add(Tema.fila(8, cancelar, ok), BorderLayout.EAST);
        t.add(pie);
        Capa capa = new Capa(capaDentro(), t, true);
        cancelar.addActionListener(a -> capa.cerrar());
        ok.addActionListener(a -> {
            Parada p = l.getSelectedValue();
            if (p == null) {
                return;
            }
            capa.cerrar();
            try {
                app.agregarASalida(s, p);
            } catch (IOException ex) {
                Aviso.mostrar(this, Aviso.Tipo.ERROR, "Salidas", ex.getMessage());
                return;
            }
            if (s.enLinea()) {
                app.revisarYa(); // la revisión la sube; si no hay internet, lo reintenta
                Aviso.mostrar(this, Aviso.Tipo.BIEN, "Entrega agregada", p.getNombre() + " le aparece al repartidor");
            }
        });
        capa.mostrar(ok);
    }

    // ------------------------------------------------------------------
    //  Mensajes
    // ------------------------------------------------------------------

    private void mensajes(Salida s) {
        CapaMensajes.abrir(capaDentro(), app, s, seguimiento);
    }

    // ------------------------------------------------------------------
    //  Foto de entrega
    // ------------------------------------------------------------------

    private void verFoto(Salida s, Salida.Entrega e) {
        if (!e.foto.isEmpty()) {
            mostrarFoto(this, e);
            return;
        }
        NubeRepartidor nube = app.getNube();
        if (nube == null || e.idNube == 0) {
            Aviso.mostrar(this, Aviso.Tipo.AVISO, "Foto", "No se puede bajar la foto sin conexión");
            return;
        }
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return nube.foto(e.idNube);
            }

            @Override
            protected void done() {
                try {
                    e.foto = get();
                    app.guardarSalida(s);
                    mostrarFoto(PanelSalidas.this, e);
                } catch (Exception ex) {
                    Aviso.mostrar(PanelSalidas.this, Aviso.Tipo.ERROR, "Foto", "No se pudo bajar la foto");
                }
            }
        }.execute();
    }

    /** La foto de entrega en grande (también la usa el historial). */
    static void mostrarFoto(JComponent dentro, Salida.Entrega e) {
        java.awt.image.BufferedImage img = decodificar(e.foto);
        if (img == null) {
            Aviso.mostrar(dentro, Aviso.Tipo.ERROR, "Foto", "La foto llegó dañada");
            return;
        }
        double k = Math.min(1, Math.min(520.0 / img.getWidth(), 420.0 / img.getHeight()));
        java.awt.Image chica = img.getScaledInstance((int) (img.getWidth() * k), (int) (img.getHeight() * k),
                java.awt.Image.SCALE_SMOOTH);
        JPanel t = Capa.tarjeta(580, 24, 28);
        JLabel titulo = new JLabel("Foto de entrega · " + e.parada.getNombre());
        titulo.setFont(Tema.subtitulo(18));
        titulo.setForeground(Tema.AZUL_PROFUNDO);
        titulo.setAlignmentX(LEFT_ALIGNMENT);
        t.add(titulo);
        t.add(Tema.espacio(10));
        JLabel foto = new JLabel(new javax.swing.ImageIcon(chica));
        foto.setAlignmentX(LEFT_ALIGNMENT);
        t.add(foto);
        t.add(Tema.espacio(12));
        Tema.Boton listo = Tema.botonSecundario("Cerrar");
        t.add(listo);
        Capa capa = new Capa(dentro, t, true);
        listo.addActionListener(a -> capa.cerrar());
        capa.mostrar(listo);
    }

    /** data:image/jpeg;base64,... a imagen (null si no se puede). */
    static java.awt.image.BufferedImage decodificar(String dataUrl) {
        try {
            int coma = dataUrl.indexOf(',');
            byte[] b = java.util.Base64.getDecoder().decode(dataUrl.substring(coma + 1));
            return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------

    private JComponent tarjetaCerradas(List<Salida> cerradas) {
        Tema.Tarjeta t = new Tema.Tarjeta();
        t.add(Tema.seccion("Cerradas hoy"));
        int plata = 0;
        for (int i = cerradas.size() - 1; i >= 0; i--) {
            Salida s = cerradas.get(i);
            int ent = s.cuantas(Salida.Entrega.ENTREGADO);
            plata += s.plataATraer();
            JLabel l = Tema.textoNormal(hora(s.creada) + (s.repartidor.isEmpty() ? "" : " · " + s.repartidor) + " · "
                    + ent + " entregada" + (ent == 1 ? "" : "s") + " · " + Tarifa.pesos(s.plataATraer()));
            l.setFont(Tema.texto(13));
            t.add(Tema.anchoLibre(l));
        }
        t.add(Tema.espacio(6));
        JLabel tot = Tema.textoNormal("Total recogido hoy: " + Tarifa.pesos(plata));
        tot.setFont(Tema.textoFuerte(13.5f));
        t.add(tot);
        t.add(Tema.espacio(8));
        Tema.Boton hist = Tema.botonPequeno("Ver historial");
        hist.setToolTipText("Las salidas de otros días, por domiciliario y con las fotos");
        hist.addActionListener(e -> {
            if (app.getVentana() != null) {
                app.getVentana().irAHistorial();
            }
        });
        t.add(hist);
        return t;
    }

    /** Las capas cubren la ventana donde está este panel. */
    private JComponent capaDentro() {
        return this;
    }

    /** Sube una salida que quedó sin asignar (o sin internet) a la página del repartidor escogido. */
    private void crearEnlace(Salida s, rutas.modelo.Domiciliario rep, Tema.Boton boton) {
        NubeRepartidor nube = app.getNube();
        NubeRepartidor.Ajustes aj = app.getAjustesNube();
        if (nube == null || aj == null || !s.abierta() || s.subiendo) {
            return;
        }
        String anterior = s.enLinea() ? s.uuidNube : ""; // si se la pasa a otro, el enlace viejo se cierra
        boton.setEnabled(false);
        boton.setText("Enviando…");
        List<Salida.Entrega> copia = new ArrayList<>();
        for (Salida.Entrega e : s.entregas) {
            copia.add(e.copia());
        }
        NubeRepartidor.Extra extra = app.extraPara(s);
        String idAntes = rep.idNube;
        s.subiendo = true;
        app.avisar();
        String codigo = NubeRepartidor.nuevoCodigo();
        new SwingWorker<NubeRepartidor.Subida, Void>() {
            @Override
            protected NubeRepartidor.Subida doInBackground() throws Exception {
                if (!anterior.isEmpty()) {
                    try {
                        nube.cerrar(anterior); // desde aquí el enlace viejo ya no muestra nada
                        nube.borrar(anterior);
                    } catch (java.io.IOException ignorado) {
                        // si no se pudo borrar, igual quedó cerrada o se borra después
                    }
                }
                extra.repartidorId = Aplicacion.repartidorEnNube(nube, rep);
                return nube.subir(codigo, rep.nombre, app.getNegocio(), copia, extra);
            }

            @Override
            protected void done() {
                s.subiendo = false;
                if (rep.id > 0 && !rep.idNube.equals(idAntes)) { // se creó en Supabase aunque la subida falle
                    try {
                        app.guardarEnlaceNube(rep);
                    } catch (java.io.IOException ex) {
                        rutas.logica.Ubicaciones.registrarError(ex);
                    }
                }
                try {
                    get().aplicar(s);
                    s.codigo = codigo;
                    s.repartidor = rep.nombre;
                    s.tipoRepartidor = rep.tipo.name();
                    s.errorNube = "";
                    s.enviadaEn = LocalDateTime.now();
                    s.avisos.clear();
                    s.aceptadaEn = null;
                    s.rechazadaEn = null;
                    s.motivoRechazo = "";
                    s.leidoNube = 0;
                    app.guardarSalida(s);
                    for (rutas.modelo.Salida.Entrega en : s.entregas) {
                        if (en.parada.getNota().isEmpty() && !en.parada.getNombre().equals(en.parada.getDireccion())) {
                            en.parada.setNota(app.notaDe(en.parada.getNombre())); // la nota no se guarda con la salida
                        }
                    }
                    String msj = rutas.logica.MensajesWhatsApp.salida(app.getNegocio(), s.creada.toLocalTime(), rep.nombre,
                            s.entregas, NubeRepartidor.enlace(aj.pagina, codigo), "", s.venceEn.toLocalTime());
                    String cel = rutas.logica.Avisos.numeroWhatsApp(rep.celular).isEmpty() ? "" : rep.celular;
                    try {
                        AbrirWhatsApp.abrir(cel, msj);
                    } catch (Exception ex) {
                        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                                .setContents(new java.awt.datatransfer.StringSelection(msj), null);
                        Aviso.mostrar(PanelSalidas.this, Aviso.Tipo.AVISO, "Mensaje copiado",
                                "No se abrió WhatsApp: péguelo en el chat de " + rep.nombre);
                    }
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    s.errorNube = c.getMessage();
                }
                app.avisar();
            }
        }.execute();
    }

    /** Mensaje «Hay domicilio» para el grupo de WhatsApp o los independientes (se escoge el chat en WhatsApp). */
    private void preguntarQuienPuede() {
        List<rutas.modelo.Parada> pend = app.getEntregas();
        int domicilios = 0;
        for (rutas.modelo.Parada p : pend) {
            domicilios += app.domicilioDe(p);
        }
        String msj = rutas.logica.MensajesWhatsApp.preguntar(app.getNegocio(), pend, domicilios);
        try {
            AbrirWhatsApp.abrir("", msj);
        } catch (Exception ex) {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(msj), null);
            Aviso.mostrar(this, Aviso.Tipo.AVISO, "Mensaje copiado", "No se abrió WhatsApp: péguelo en el grupo");
        }
    }
}
