package rutas.vista;

import java.awt.Component;
import java.util.function.BiConsumer;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import rutas.logica.Granel;
import rutas.logica.ImportadorProductos;
import rutas.logica.Tarifa;
import rutas.modelo.Producto;

/**
 * "¿Cuánto?" para productos a granel: por valor ("dos mil de azúcar") o por peso (1,5 lb).
 * Devuelve (true, pesos) si fue por valor o (false, milesimas) si fue por peso.
 */
final class CuadroGranel {

    private CuadroGranel() {
    }

    static void mostrar(JComponent dentroDe, Producto p, BiConsumer<Boolean, Long> alAgregar) {
        JPanel t = Capa.tarjeta(460, 24, 28);
        t.add(Tema.tituloPantalla(p.getNombre()));
        t.add(Tema.anchoLibre(Tema.textoNormal("Precio: " + Tarifa.pesos(p.getPrecioVenta()) + " por "
                + (p.getUnidad() == rutas.modelo.Unidad.LIBRA ? "libra" : "kilo"))));
        t.add(Tema.espacio(14));

        Tema.Boton porValor = Tema.botonSecundario("Por valor");
        Tema.Boton porPeso = Tema.botonSecundario("Por peso (" + p.getUnidad().getCorto() + ")");
        t.add(Tema.fila(8, porValor, porPeso));
        t.add(Tema.espacio(12));

        JLabel etiqueta = Tema.etiqueta("¿Cuánta plata?");
        t.add(etiqueta);
        JTextField campo = Tema.campo(null);
        campo.setFont(Tema.subtitulo(22));
        campo.setHorizontalAlignment(JTextField.RIGHT);
        campo.getAccessibleContext().setAccessibleName("Cantidad");
        Tema.anchoCompleto(campo);
        t.add(campo);
        t.add(Tema.espacio(8));

        JPanel rapidos = Tema.fila(6);
        for (long v : new long[]{500, 1000, 2000, 3000, 5000}) {
            Tema.Boton b = Tema.botonPequeno(Tarifa.pesos(v));
            b.addActionListener(e -> {
                campo.setText(Tarifa.pesos(v).replace("$", ""));
                campo.requestFocusInWindow();
            });
            rapidos.add(b);
        }
        t.add(rapidos);
        t.add(Tema.espacio(10));

        JLabel resultado = new JLabel(" ");
        resultado.setFont(Tema.subtitulo(17));
        resultado.setForeground(Tema.GRIS_OSCURO);
        resultado.setAlignmentX(Component.LEFT_ALIGNMENT);
        t.add(Tema.anchoLibre(resultado));
        t.add(Tema.espacio(16));

        Tema.Boton agregar = Tema.botonPrimario("Agregar a la venta");
        Tema.Boton volver = Tema.botonSecundario("Volver");
        t.add(Tema.fila(8, agregar, volver));

        boolean[] esValor = {true};
        long[] valor = {-1};
        Runnable calcular = () -> {
            valor[0] = -1;
            String txt = campo.getText().trim();
            if (txt.isEmpty()) {
                resultado.setText(" ");
                agregar.setEnabled(false);
                return;
            }
            try {
                if (esValor[0]) {
                    long pesos = ImportadorProductos.leerPesos(txt);
                    long cant = Granel.cantidadPorValor(pesos, p.getPrecioVenta());
                    resultado.setForeground(Tema.GRIS_OSCURO);
                    resultado.setText(peso(cant, p) + "  ·  " + Tarifa.pesos(pesos));
                    valor[0] = pesos;
                } else {
                    long cant = ImportadorProductos.leerCantidad(txt);
                    if (cant <= 0) {
                        throw new IllegalArgumentException("La cantidad debe ser mayor que cero");
                    }
                    resultado.setForeground(Tema.GRIS_OSCURO);
                    resultado.setText(peso(cant, p) + "  ·  " + Tarifa.pesos((p.getPrecioVenta() * cant + 500) / 1000));
                    valor[0] = cant;
                }
            } catch (IllegalArgumentException ex) {
                resultado.setForeground(Tema.ERROR_TEXTO);
                resultado.setText(ex.getMessage());
            }
            agregar.setEnabled(valor[0] > 0);
        };
        Runnable modo = () -> {
            porValor.setSelected(esValor[0]);
            porPeso.setSelected(!esValor[0]);
            etiqueta.setText(esValor[0] ? "¿Cuánta plata?" : "¿Cuántas " + (p.getUnidad() == rutas.modelo.Unidad.LIBRA
                    ? "libras?" : "kilos? (ej. 1,5 o 0,25)"));
            rapidos.setVisible(esValor[0]);
            campo.setText("");
            calcular.run();
            campo.requestFocusInWindow();
        };
        porValor.addActionListener(e -> {
            esValor[0] = true;
            modo.run();
        });
        porPeso.addActionListener(e -> {
            esValor[0] = false;
            modo.run();
        });
        campo.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { calcular.run(); }
            public void removeUpdate(DocumentEvent e) { calcular.run(); }
            public void changedUpdate(DocumentEvent e) { calcular.run(); }
        });

        Capa capa = new Capa(dentroDe, t, true);
        Runnable hacer = () -> {
            if (valor[0] > 0) {
                capa.cerrar();
                alAgregar.accept(esValor[0], valor[0]);
            }
        };
        agregar.addActionListener(e -> hacer.run());
        campo.addActionListener(e -> hacer.run());
        volver.addActionListener(e -> capa.cerrar());
        modo.run();
        capa.mostrar(campo);
    }

    /** 455 -> "455 g" en kilos; 1667 -> "1,67 lb" en libras (dos decimales bastan para leerlo). */
    static String peso(long milesimas, Producto p) {
        if (p.getUnidad() == rutas.modelo.Unidad.KILO && milesimas < 1000) {
            return milesimas + " g";
        }
        java.math.BigDecimal b = java.math.BigDecimal.valueOf(milesimas, 3)
                .setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        String n = (b.scale() < 0 ? b.setScale(0) : b).toPlainString().replace('.', ',');
        return n + " " + p.getUnidad().getCorto();
    }
}
