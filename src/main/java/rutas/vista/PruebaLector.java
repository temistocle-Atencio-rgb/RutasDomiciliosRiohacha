package rutas.vista;

import java.awt.Font;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * "Probar el lector": dice si el celular esta conectado y muestra lo que llega de cualquier lector.
 * Mientras esta abierta, lo que se escanea se muestra aqui y no entra a la venta.
 */
final class PruebaLector {

    private PruebaLector() {
    }

    static void mostrar(JComponent dentroDe) {
        Lector lector = Lector.get();
        JPanel t = Capa.tarjeta(560, 24, 28);
        t.add(Tema.tituloPantalla("Probar el lector"));
        t.add(Tema.espacio(6));
        t.add(Tema.parrafo("Escanee cualquier producto. Mientras esta ventana esté abierta, el código se muestra "
                + "aquí y no se agrega a la venta.", 500));
        t.add(Tema.espacio(16));

        t.add(Tema.seccion("Celular por cable USB"));
        t.add(Tema.espacio(6));
        Tema.Parrafo estado = Tema.parrafo("", 500);
        estado.setFont(Tema.textoFuerte(14));
        estado.getAccessibleContext().setAccessibleName("Estado del celular");
        t.add(estado);
        t.add(Tema.espacio(4));
        t.add(Tema.parrafo("Active la depuración USB en el celular (una sola vez) y conéctelo: la primera vez DomiRuta "
                + "pregunta si lo va a usar como lector; si dice que sí, le instala la app DomiRuta Lector y la abre. "
                + "Sin la app, en Chrome del celular abra localhost:8765.", 500));
        Tema.Boton olvidar = Tema.botonPlano("Volver a preguntar por los celulares");
        olvidar.setToolTipText("Olvida qué celulares se usan como lector: al conectarlos se pregunta otra vez");
        t.add(Tema.fila(0, olvidar));
        t.add(Tema.espacio(16));

        t.add(Tema.seccion("Lector USB (pistola)"));
        t.add(Tema.espacio(6));
        t.add(Tema.parrafo("Conéctelo y escanee: funciona como un teclado. Si el código cae en otro lado de la "
                + "venta (por ejemplo una celda de la tabla), DomiRuta lo lleva al buscador.", 500));
        t.add(Tema.espacio(8));
        JTextField campo = Tema.campo("Escanee aquí con el lector USB");
        campo.getAccessibleContext().setAccessibleName("Campo de prueba para el lector USB");
        campo.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        t.add(campo);
        t.add(Tema.espacio(16));

        t.add(Tema.seccion("Último código"));
        t.add(Tema.espacio(6));
        JLabel codigo = new JLabel("Todavía no ha llegado ninguno");
        codigo.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
        codigo.setForeground(Tema.GRIS_TEXTO);
        codigo.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        codigo.getAccessibleContext().setAccessibleName("Último código leído");
        t.add(codigo);
        JLabel origen = new JLabel(" ");
        origen.setFont(Tema.texto(13.5f));
        origen.setForeground(Tema.GRIS_TEXTO);
        origen.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        t.add(origen);
        t.add(Tema.espacio(18));

        Tema.Boton cerrar = Tema.botonPrimario("Listo");
        t.add(Tema.fila(8, cerrar));

        Capa capa = new Capa(dentroDe, t, true);
        Runnable pintar = () -> {
            estado.setText(lector.textoCelular());
            estado.setForeground(lector.celularListo() ? Tema.EXITO_TEXTO : Tema.GRIS_OSCURO);
        };
        pintar.run();
        lector.alCambiar(pintar);
        int[] cuantos = {0};
        lector.setPrueba((c, fuente) -> {
            cuantos[0]++;
            codigo.setText(c);
            codigo.setForeground(Tema.GRIS_OSCURO);
            origen.setText("Llegó por " + fuente.getTexto() + "  ·  " + cuantos[0]
                    + (cuantos[0] == 1 ? " código en esta prueba" : " códigos en esta prueba"));
            campo.setText("");
            capa.reacomodar();
        });
        capa.setAlCerrar(() -> {
            lector.setPrueba(null);
            lector.quitarOyente(pintar);
        });
        cerrar.addActionListener(e -> capa.cerrar());
        olvidar.addActionListener(e -> {
            lector.olvidarCelulares();
            olvidar.setText("Listo: al conectar un celular se preguntará otra vez");
            capa.reacomodar();
        });
        capa.mostrar(campo);
    }
}
