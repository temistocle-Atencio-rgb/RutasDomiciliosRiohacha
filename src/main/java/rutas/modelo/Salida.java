package rutas.modelo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Una salida del repartidor: las entregas que se llevó en una vuelta.
 * Pasa por En camino -> Regresando (ya marcó todas) -> Cerrada (volvió a la tienda y se cuadró).
 * Si hay internet, la salida también vive en Supabase (uuidNube) y el repartidor la ve en el celular
 * con el enlace que lleva el código.
 */
public class Salida {

    public enum Estado {
        EN_CAMINO("En camino"), REGRESANDO("Regresando"), CERRADA("Cerrada");

        private final String texto;

        Estado(String texto) {
            this.texto = texto;
        }

        @Override
        public String toString() {
            return texto;
        }
    }

    /** Una parada de la salida, con lo que el repartidor marcó. */
    public static class Entrega {
        public static final String EN_CAMINO = "en_camino";
        public static final String ENTREGADO = "entregado";
        public static final String NO_ENTREGADO = "no_entregado";

        public long id;            // en la base del computador
        public long idNube;        // en Supabase (0 si no se subió)
        public final Parada parada;
        public int cobrar;         // lo que cobra en la puerta (0 = ya pagó)
        public int domicilio;      // valor del domicilio de esta entrega
        public String estado = EN_CAMINO;
        public String motivo = "";
        public boolean tieneFoto;
        public String foto = "";   // data:image/jpeg;base64,... (se baja al cerrar la salida)
        /** La tienda la marcó a mano y falta pasarlo a internet (se guarda: se reintenta aunque se cierre el programa). */
        public boolean marcaPendiente;
        public long marcadaEn;
        /** Para que el cliente vea por dónde va su pedido (los da Supabase al subir la salida). */
        public String codigoCliente = "";
        public String enlaceCliente = "";

        /** Copia para subir desde otro hilo (lo que la pantalla cambie después no la afecta). */
        public Entrega copia() {
            Entrega c = new Entrega(parada, cobrar);
            c.domicilio = domicilio;
            c.estado = estado;
            c.motivo = motivo;
            return c;
        }

        public Entrega(Parada parada, int cobrar) {
            this.parada = parada;
            this.cobrar = Math.max(0, cobrar);
        }

        public boolean entregada() {
            return ENTREGADO.equals(estado);
        }

        public boolean noEntregada() {
            return NO_ENTREGADO.equals(estado);
        }

        public boolean enCamino() {
            return EN_CAMINO.equals(estado);
        }

        public String textoEstado() {
            if (entregada()) {
                return "Entregado";
            }
            if (noEntregada()) {
                return motivo.isEmpty() ? "No entregado" : "No entregado · " + motivo;
            }
            return "En camino";
        }
    }

    /** Un mensaje entre la tienda y el repartidor. */
    public static class Mensaje {
        public long idNube;
        public final boolean deTienda;
        public final String texto;
        public final LocalDateTime hora;
        public boolean leido;

        public Mensaje(long idNube, boolean deTienda, String texto, LocalDateTime hora, boolean leido) {
            this.idNube = idNube;
            this.deTienda = deTienda;
            this.texto = texto;
            this.hora = hora;
            this.leido = leido;
        }
    }

    public long id;
    public String codigo = "";     // va en el enlace del repartidor (vacío si no se subió a internet)
    public String uuidNube = "";
    public String repartidor = "";
    /** FIJO, INDEPENDIENTE o GRUPO ("" = sin saber): los no fijos se quedan con los domicilios. */
    public String tipoRepartidor = "";
    public LocalDateTime creada = LocalDateTime.now();
    public LocalDateTime cerrada;
    public Estado estado = Estado.EN_CAMINO;
    public double lat = Double.NaN;  // última ubicación del repartidor
    public double lon = Double.NaN;
    public LocalDateTime ubicacionEn;
    /** Por dónde ha pasado el repartidor ({lat, lon, id}); no se guarda: se vuelve a leer de internet. */
    public final java.util.List<double[]> rastro = new java.util.ArrayList<>();
    public long rastroHasta;
    public LocalDateTime aceptadaEn;     // el domiciliario aceptó (null = todavía no responde)
    public LocalDateTime rechazadaEn;    // dijo que no puede
    public String motivoRechazo = "";
    public LocalDateTime enviadaEn;      // cuándo se le mandó el enlace (para saber si no responde)
    public final List<Entrega> entregas = new ArrayList<>();
    public final List<Mensaje> mensajes = new ArrayList<>();
    /** Mientras se sube a internet no se puede cerrar ni agregarle entregas. */
    public transient boolean subiendo;
    /** Último mensaje leído de internet (los que escribe la tienda no cuentan: así no se salta ninguno). */
    public transient long leidoNube;
    /** Por qué no se pudo subir (se muestra en la tarjeta). */
    public transient String errorNube = "";
    /** Cuándo marcó la última (0 = no ha terminado); se espera un rato antes de cerrarla sola. */
    public transient long terminoEn;
    /** Ya se avisó en la caja que terminó (para no avisar dos veces ni dejar de avisar). */
    public transient boolean avisadoTermino;
    /** Hasta cuándo puede aceptar el repartidor (null = no vence). */
    public LocalDateTime venceEn;
    /** El repartidor apagó «Compartir mi ubicación». */
    public transient boolean ubicacionPausada;
    /** Ya se leyó de Supabase desde que se abrió el programa (antes no se sabe si pausó la ubicación). */
    public transient boolean leidaEnLinea;
    /** Avisos de la campana que ya salieron para esta salida (para no repetirlos). */
    public final transient java.util.Set<String> avisos = new java.util.HashSet<>();

    /** Se le venció el tiempo para aceptar sin decir nada. */
    public boolean vencida() {
        return esperandoRespuesta() && venceEn != null && LocalDateTime.now().isAfter(venceEn);
    }

    public boolean enLinea() {
        return !uuidNube.isEmpty();
    }

    /** Se le mandó el enlace y no ha dicho ni sí ni no. */
    public boolean esperandoRespuesta() {
        return enLinea() && aceptadaEn == null && rechazadaEn == null;
    }

    public boolean abierta() {
        return estado != Estado.CERRADA;
    }

    /** Plata que debe traer: lo que se cobra en las entregadas. */
    public int plataATraer() {
        int t = 0;
        for (Entrega e : entregas) {
            if (e.entregada()) {
                t += e.cobrar;
            }
        }
        return t;
    }

    /** Valor de los domicilios entregados (un independiente se queda con esto). */
    public int domiciliosEntregados() {
        int t = 0;
        for (Entrega e : entregas) {
            if (e.entregada()) {
                t += e.domicilio;
            }
        }
        return t;
    }

    public boolean seQuedaConDomicilios() {
        return tipoRepartidor.equals("INDEPENDIENTE") || tipoRepartidor.equals("GRUPO");
    }

    /**
     * Lo que el domiciliario le debe entregar a la tienda por lo entregado: un fijo trae todo lo que cobró;
     * un independiente o grupo se queda con los domicilios. Negativo = la tienda le debe a él.
     */
    public int debeEntregar() {
        return seQuedaConDomicilios() ? plataATraer() - domiciliosEntregados() : plataATraer();
    }

    /** Ya marcó todas (ninguna sigue en camino). */
    public boolean todasMarcadas() {
        return !entregas.isEmpty() && cuantas(Entrega.EN_CAMINO) == 0;
    }

    /** Las que no se entregaron: esa bolsa tiene que volver a la tienda. */
    public List<Entrega> bolsasPorDevolver() {
        List<Entrega> l = new ArrayList<>();
        for (Entrega e : entregas) {
            if (e.noEntregada()) {
                l.add(e);
            }
        }
        return l;
    }

    /** Terminó, todo entregado y no hay plata que cuadrar: se cierra sola. */
    public boolean cierraSola() {
        return abierta() && !subiendo && todasMarcadas() && bolsasPorDevolver().isEmpty() && debeEntregar() == 0;
    }

    /** Plata total si se entregan todas. */
    public int plataTotal() {
        int t = 0;
        for (Entrega e : entregas) {
            t += e.cobrar;
        }
        return t;
    }

    public int cuantas(String estadoEntrega) {
        int n = 0;
        for (Entrega e : entregas) {
            if (e.estado.equals(estadoEntrega)) {
                n++;
            }
        }
        return n;
    }

    public int mensajesSinLeer() {
        int n = 0;
        for (Mensaje m : mensajes) {
            if (!m.deTienda && !m.leido) {
                n++;
            }
        }
        return n;
    }

    public boolean tieneMensaje(long idNube) {
        for (Mensaje m : mensajes) {
            if (m.idNube == idNube) {
                return true;
            }
        }
        return false;
    }

    public long ultimoMensaje() {
        long max = 0;
        for (Mensaje m : mensajes) {
            max = Math.max(max, m.idNube);
        }
        return max;
    }

    /** Si ya no queda ninguna en camino pasa a Regresando; si se deshizo una, vuelve a En camino. */
    public void revisarEstado() {
        if (estado == Estado.CERRADA) {
            return;
        }
        estado = cuantas(Entrega.EN_CAMINO) == 0 && !entregas.isEmpty() ? Estado.REGRESANDO : Estado.EN_CAMINO;
        if (estado == Estado.EN_CAMINO) { // tocó «Deshacer» o le agregaron una entrega
            terminoEn = 0;
            avisadoTermino = false;
        } else if (terminoEn == 0) {
            terminoEn = System.currentTimeMillis();
        }
    }

    public Entrega buscarNube(long idNube) {
        for (Entrega e : entregas) {
            if (e.idNube == idNube) {
                return e;
            }
        }
        return null;
    }
}
