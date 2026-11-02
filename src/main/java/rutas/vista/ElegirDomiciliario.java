package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import rutas.modelo.Domiciliario;

/**
 * «¿Quién lleva esta salida?»: un botón por domiciliario de la lista (los fijos primero) y «Otro»
 * para escribir el nombre y el celular de alguien nuevo ahí mismo (se puede guardar en la lista).
 * La lista está en este computador: funciona aunque no haya internet.
 */
final class ElegirDomiciliario {

    private ElegirDomiciliario() {
    }

    private static int orden(Aplicacion.Disponibilidad d) {
        switch (d) {
            case DISPONIBLE: return 0;
            case SIN_APP: return 1;
            case NO_DISPONIBLE: return 2;
            default: return 3; // en ruta
        }
    }

    /** «Disponible · Fijo», «En ruta · le faltan 2», «No disponible» o solo el tipo si no usa la app. */
    static String textoEstado(Aplicacion app, Domiciliario d, Aplicacion.Disponibilidad disp) {
        switch (disp) {
            case DISPONIBLE: return "Disponible";
            case EN_RUTA:
                int n = app.pendientesDe(d);
                return "En ruta · " + (n == 1 ? "le falta 1" : "le faltan " + n);
            case NO_DISPONIBLE: return "No disponible";
            default: return d.tipo.toString();
        }
    }

    /** alElegir recibe el domiciliario escogido (nunca null). Si se cancela no pasa nada. */
    static void abrir(JComponent dentroDe, Aplicacion app, String subtitulo, Consumer<Domiciliario> alElegir) {
        JPanel t = Capa.tarjeta(500, 24, 28);
        JLabel titulo = new JLabel("¿Quién lleva esta salida?");
        titulo.setFont(Tema.subtitulo(18));
        titulo.setForeground(Tema.AZUL_PROFUNDO);
        titulo.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        t.add(titulo);
        JLabel sub = new JLabel(subtitulo);
        sub.setFont(Tema.texto(13.5f));
        sub.setForeground(Tema.GRIS_TEXTO);
        sub.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        t.add(sub);
        t.add(Tema.espacio(12));

        Capa[] capa = new Capa[1];
        boolean[] hecho = {false};
        Consumer<Domiciliario> elegir = d -> {
            if (hecho[0]) {
                return;
            }
            hecho[0] = true;
            capa[0].cerrar();
            alElegir.accept(d);
        };

        List<Domiciliario> lista = app.getDomiciliarios();
        lista.removeIf(d -> !d.activo);
        // los que marcaron «Disponible» en su app van primero; los que van en ruta o no están, al final
        java.util.Map<Domiciliario, Aplicacion.Disponibilidad> estado = new java.util.HashMap<>();
        for (Domiciliario d : lista) {
            estado.put(d, app.disponibilidad(d));
        }
        lista.sort(java.util.Comparator.comparingInt(d -> orden(estado.get(d)))); // estable: dentro, como estaban
        Tema.Boton primero = null;
        for (Domiciliario d : lista) {
            Aplicacion.Disponibilidad disp = estado.get(d);
            boolean atras = disp == Aplicacion.Disponibilidad.EN_RUTA || disp == Aplicacion.Disponibilidad.NO_DISPONIBLE;
            Tema.Boton b = primero == null && !atras ? Tema.botonPrimario(d.nombre) : Tema.botonSecundario(d.nombre);
            b.setToolTipText(d.tipo + (d.celular.isEmpty() ? "" : " · Cel. " + d.celular));
            b.addActionListener(a -> elegir.accept(d));
            JLabel tipo = new JLabel(textoEstado(app, d, disp));
            tipo.setFont(atras || disp == Aplicacion.Disponibilidad.SIN_APP ? Tema.texto(12.5f) : Tema.textoFuerte(12.5f));
            tipo.setForeground(disp == Aplicacion.Disponibilidad.DISPONIBLE ? Tema.EXITO_TEXTO : Tema.GRIS_TEXTO);
            JPanel fila = new JPanel(new BorderLayout(10, 0));
            fila.setOpaque(false);
            fila.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            fila.add(b, BorderLayout.CENTER);
            JPanel der = new JPanel(new BorderLayout());
            der.setOpaque(false);
            der.setPreferredSize(new Dimension(150, 10));
            der.add(tipo, BorderLayout.CENTER);
            fila.add(der, BorderLayout.EAST);
            fila.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
            t.add(fila);
            t.add(Tema.espacio(6));
            if (primero == null && !atras) {
                primero = b;
            }
        }

        // Otro: alguien que no está en la lista (por ejemplo un independiente que respondió)
        JPanel otro = new JPanel();
        otro.setOpaque(false);
        otro.setLayout(new BoxLayout(otro, BoxLayout.Y_AXIS));
        otro.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        otro.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(lista.isEmpty() ? 0 : 1, 0, 0, 0,
                Tema.BORDE), BorderFactory.createEmptyBorder(lista.isEmpty() ? 0 : 10, 0, 0, 0)));
        JLabel tOtro = new JLabel(lista.isEmpty() ? "Escriba quién la lleva" : "Otro");
        tOtro.setFont(Tema.textoFuerte(13.5f));
        tOtro.setForeground(Tema.GRIS_OSCURO);
        tOtro.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        otro.add(tOtro);
        otro.add(Tema.espacio(6));
        JTextField nombre = Tema.campo("Nombre");
        JTextField celular = Tema.campo("Celular (WhatsApp)");
        Tema.nombreAccesible(nombre, "Nombre del domiciliario");
        Tema.nombreAccesible(celular, "Celular del domiciliario");
        JComboBox<Domiciliario.Tipo> tipo = new JComboBox<>(Domiciliario.Tipo.values());
        tipo.setSelectedItem(Domiciliario.Tipo.INDEPENDIENTE);
        Tema.estiloCombo(tipo, String::valueOf);
        JPanel campos = new JPanel(new java.awt.GridLayout(1, 3, 8, 0));
        campos.setOpaque(false);
        campos.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        campos.add(nombre);
        campos.add(celular);
        campos.add(tipo);
        campos.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        otro.add(campos);
        otro.add(Tema.espacio(6));
        javax.swing.JCheckBox guardar = Tema.casilla("Guardarlo en la lista");
        guardar.setSelected(true);
        otro.add(guardar);
        JLabel error = new JLabel(" ");
        error.setFont(Tema.textoFuerte(12.5f));
        error.setForeground(Tema.ERROR_TEXTO);
        error.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        otro.add(error);
        Tema.Boton usarOtro = lista.isEmpty() ? Tema.botonPrimario("Que la lleve") : Tema.botonSecundario("Que la lleve");
        Runnable conOtro = () -> {
            String n = nombre.getText().trim();
            if (n.length() < 2) {
                error.setText("Escriba el nombre");
                nombre.requestFocusInWindow();
                return;
            }
            Domiciliario d = new Domiciliario(0, n, celular.getText(), (Domiciliario.Tipo) tipo.getSelectedItem());
            if (guardar.isSelected()) {
                try {
                    app.guardarDomiciliario(d);
                } catch (java.io.IOException ex) {
                    error.setText(ex.getMessage());
                    return;
                }
            }
            elegir.accept(d);
        };
        usarOtro.addActionListener(a -> conOtro.run());
        nombre.addActionListener(a -> celular.requestFocusInWindow());
        celular.addActionListener(a -> conOtro.run());
        otro.add(usarOtro);
        t.add(otro);
        t.add(Tema.espacio(12));
        Tema.Boton cancelar = Tema.botonSecundario("Cancelar");
        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        pie.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        pie.add(cancelar, BorderLayout.EAST);
        VentanaPrincipal v = app.getVentana();
        if (v != null && v.puedeEditarDomiciliarios()) { // aquí es donde uno ve que un número está mal
            Tema.Boton editar = Tema.botonPlano("Editar la lista");
            editar.setToolTipText("Cambiar nombre, celular o tipo, desactivar o quitar (Configuración › Domiciliarios)");
            editar.addActionListener(a -> {
                capa[0].cerrar();
                v.irADomiciliarios();
            });
            pie.add(editar, BorderLayout.WEST);
        }
        t.add(pie);

        capa[0] = new Capa(dentroDe, t, true);
        cancelar.addActionListener(a -> capa[0].cerrar());
        capa[0].mostrar(primero != null ? primero : nombre);
    }
}
