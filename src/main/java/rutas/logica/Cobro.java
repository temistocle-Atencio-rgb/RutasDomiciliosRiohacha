package rutas.logica;

import java.util.List;
import rutas.modelo.MetodoPago;
import rutas.modelo.Pago;

/**
 * Revisa si lo que paga el cliente alcanza y calcula las vueltas.
 * Las vueltas solo salen del efectivo: si pagan por Nequi de mas, no hay vueltas.
 */
public final class Cobro {

    /** Lo maximo que el efectivo puede pasar del total (un billete de $100.000). */
    public static final long MAX_SOBRA_EFECTIVO = 100_000;

    private Cobro() {
    }

    public static Resultado calcular(long total, List<Pago> pagos) {
        long efectivo = 0;
        long otros = 0;
        for (Pago p : pagos) {
            if (p.getMetodo() == MetodoPago.EFECTIVO) {
                efectivo += p.getValor();
            } else {
                otros += p.getValor();
            }
        }
        if (otros > total) {
            return new Resultado(false, 0, 0, efectivo, "El pago pasa del total");
        }
        // nadie paga con mas de $100.000 de sobra: un numero asi es un codigo escaneado en el campo
        if (efectivo > total + MAX_SOBRA_EFECTIVO) {
            return new Resultado(false, 0, 0, efectivo, "Revise lo recibido: pasa del total en más de $100.000");
        }
        long pagado = efectivo + otros;
        if (pagado < total) {
            return new Resultado(false, total - pagado, 0, efectivo, null);
        }
        return new Resultado(true, 0, pagado - total, efectivo, null);
    }

    /** Lo que salio de la cuenta. */
    public static class Resultado {
        private final boolean alcanza;
        private final long faltan;
        private final long vueltas;
        private final long recibidoEfectivo;
        private final String error;

        Resultado(boolean alcanza, long faltan, long vueltas, long recibidoEfectivo, String error) {
            this.alcanza = alcanza;
            this.faltan = faltan;
            this.vueltas = vueltas;
            this.recibidoEfectivo = recibidoEfectivo;
            this.error = error;
        }

        public boolean isAlcanza() {
            return alcanza;
        }

        public long getFaltan() {
            return faltan;
        }

        public long getVueltas() {
            return vueltas;
        }

        public long getRecibidoEfectivo() {
            return recibidoEfectivo;
        }

        public String getError() {
            return error;
        }
    }
}
