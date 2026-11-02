package rutas.modelo;

/**
 * Quien lleva los domicilios. No necesita cuenta ni contraseña: a cada salida le llega por WhatsApp
 * un enlace solo para esa salida.
 * - FIJO: trabaja con la tienda.
 * - INDEPENDIENTE: presta el servicio cuando está disponible (se le pregunta) y se queda con los domicilios.
 * - GRUPO: un grupo de domicilios con nombre; el celular es el de quien reparte los pedidos.
 */
public class Domiciliario {

    public enum Tipo {
        FIJO("Fijo de la tienda"), INDEPENDIENTE("Independiente"), GRUPO("Grupo de domicilios");

        private final String texto;

        Tipo(String texto) {
            this.texto = texto;
        }

        @Override
        public String toString() {
            return texto;
        }

        /** El fijo le entrega todo a la tienda; los demás se quedan con el valor de los domicilios. */
        public boolean seQuedaConDomicilios() {
            return this != FIJO;
        }
    }

    public long id;
    public String nombre;
    public String celular;
    public Tipo tipo;
    public boolean activo = true;

    public Domiciliario(long id, String nombre, String celular, Tipo tipo) {
        this.id = id;
        this.nombre = nombre == null ? "" : nombre.trim();
        this.celular = celular == null ? "" : celular.trim();
        this.tipo = tipo == null ? Tipo.FIJO : tipo;
    }

    @Override
    public String toString() {
        return nombre;
    }
}
