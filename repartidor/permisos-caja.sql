-- DomiRuta: permisos para la caja (clave secreta = rol service_role).
-- Hace falta porque el proyecto se creó con "exponer tablas automáticamente" apagado.
-- La clave pública (anon) sigue sin poder tocar las tablas: solo usa las funciones.
grant usage on schema public to service_role;
grant select, insert, update, delete on public.salidas, public.entregas_salida, public.mensajes_salida to service_role;
grant usage, select on all sequences in schema public to service_role;
