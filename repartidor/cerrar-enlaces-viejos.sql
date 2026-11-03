-- DomiRuta, paso 2: desde aquí el repartidor solo entra con su celular y su clave.
-- Correr SOLO cuando la página nueva del repartidor y DomiRuta nuevo ya estén subidos (si no, la página vieja deja de servir).
-- La página del cliente no cambia (ver_pedido y ver_pedido_codigo siguen abiertas: solo muestran su pedido).
do $$
declare
  f text;
begin
  foreach f in array array[
    'public.info_salida(text)', 'public.ruta_salida(text)', 'public.responder_salida(text, boolean, text)',
    'public.ver_salida(text)',
    'public.enviar_posicion(text, double precision, double precision, double precision, double precision, double precision)',
    'public.enviar_ubicacion(text, double precision, double precision)', 'public.pausar_ubicacion(text, boolean)',
    'public.marcar_entrega(text, bigint, text, text)',
    'public.marcar_entrega_gps(text, bigint, text, text, double precision, double precision)',
    'public.ver_mensajes(text, bigint)', 'public.enviar_mensaje(text, text)', 'public.subir_foto(text, bigint, text)',
    'public.corregir_ubicacion(text, bigint, double precision, double precision, double precision, double precision)',
    'public.mis_salidas(text, text)', 'public.confirmar_dispositivo(text, text)',
    'public.cambiar_disponible(text, text, boolean)', 'public.guardar_suscripcion(text, text, text, text, text)']
  loop
    execute 'revoke all on function ' || f || ' from public, anon, authenticated';
  end loop;
end $$;
