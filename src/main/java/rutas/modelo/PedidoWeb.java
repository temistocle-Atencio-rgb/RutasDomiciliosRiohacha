package rutas.modelo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Un pedido que llegó de la tienda en línea (lo hizo el cliente en la página). */
public class PedidoWeb {

    public static class Item {
        public long id;
        public final String nombre;
        public final long precio;
        public final int cantidad;

        public Item(String nombre, long precio, int cantidad) {
            this.nombre = nombre;
            this.precio = precio;
            this.cantidad = cantidad;
        }
    }

    public long id;
    public String codigo = "";
    public LocalDateTime creado;
    public String nombre = "";
    public String celular = "";
    public String entrega = "domicilio";   // domicilio | retiro
    public String direccion = "";
    public String referencia = "";
    public Double lat;
    public Double lon;
    public String pago = "efectivo";       // efectivo, nequi, ... (como MetodoPago en minúscula)
    public long pagaCon;
    public String nota = "";
    public final List<Item> items = new ArrayList<>();
    public long subtotal;
    public long domicilio;
    public long total;
    public String estado = "nuevo";        // nuevo, aceptado, preparando, en_camino, entregado, cancelado
    public String motivo = "";
    public boolean tomado;
    public String comprobante;             // ruta en la carpeta privada (null = no ha mandado)
    public boolean pagado;
    /** Productos que el encargado marcó «No hay» al empacar (no se venden ni se cobran). */
    public final List<Long> faltantes = new ArrayList<>();
    public long venta;                     // número del recibo en DomiRuta (0 = todavía no)

    /** Lo que de verdad se lleva (sin lo que faltó). */
    public long totalEmpacado() {
        long t = domicilio;
        for (Item i : items) {
            if (!faltantes.contains(i.id)) {
                t += i.precio * i.cantidad;
            }
        }
        return t;
    }

    public boolean aDomicilio() {
        return !"retiro".equals(entrega);
    }

    /** Se paga antes por Nequi, transferencia...: hay que esperar el comprobante. */
    public boolean pagaAntes() {
        return !pago.equals("efectivo") && !pago.startsWith("tarjeta_");
    }

    public MetodoPago metodo() {
        try {
            return MetodoPago.valueOf(pago.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MetodoPago.EFECTIVO;
        }
    }

    public boolean terminado() {
        return estado.equals("entregado") || estado.equals("cancelado");
    }

    public int unidades() {
        int n = 0;
        for (Item i : items) {
            n += i.cantidad;
        }
        return n;
    }

    public String estadoTexto() {
        switch (estado) {
            case "nuevo": return "Nuevo";
            case "aceptado": return "Aceptado";
            case "preparando": return aDomicilio() ? "En la ruta" : "Alistando";
            case "en_camino": return aDomicilio() ? "Va en camino" : "Listo para recoger";
            case "entregado": return "Entregado";
            case "cancelado": return "Cancelado";
            default: return estado;
        }
    }
}
