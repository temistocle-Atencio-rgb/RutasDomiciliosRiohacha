package rutas.logica;

/**
 * Cuadre de caja de una moto: cuanto se cobro en domicilios y en pedidos,
 * y cuanto efectivo tiene que entregar el repartidor al volver.
 */
public class Cuadre {

    private long domicilios;
    private long pedidos;
    private long efectivo;
    private long transferido;
    private int entregas;

    /**
     * @param pedido    valor del pedido (0 si no se sabe)
     * @param domicilio valor del domicilio
     * @param pagado    true si el cliente ya pago por transferencia
     */
    public void agregar(long pedido, long domicilio, boolean pagado) {
        domicilios += domicilio;
        pedidos += pedido;
        entregas++;
        if (pagado) {
            transferido += pedido + domicilio;
        } else {
            efectivo += pedido + domicilio;
        }
    }

    public long getDomicilios() { return domicilios; }
    public long getPedidos() { return pedidos; }
    /** Lo que el repartidor recibe en la calle y debe entregar en caja. */
    public long getEfectivo() { return efectivo; }
    public long getTransferido() { return transferido; }
    public int getEntregas() { return entregas; }

    /** Texto para mandar o guardar: el cuadre de una moto. */
    public String texto(String titulo) {
        return titulo + "\n"
                + entregas + " entregas\n"
                + "Domicilios: " + Tarifa.pesos(domicilios) + "\n"
                + "Pedidos: " + Tarifa.pesos(pedidos) + "\n"
                + "Pagado por transferencia: " + Tarifa.pesos(transferido) + "\n"
                + "Efectivo que debe entregar: " + Tarifa.pesos(efectivo);
    }
}
