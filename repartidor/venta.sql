-- DomiRuta: número de la venta y cuántos productos lleva cada entrega (para saber qué bolsa es de quién).
-- Se corre después de simplificar.sql. Se puede correr otra vez.
alter table public.entregas_salida add column if not exists venta text not null default '';
alter table public.entregas_salida add column if not exists productos int not null default 0;

drop function if exists public.ver_salida(text);
create function public.ver_salida(p_codigo text)
returns table (id bigint, orden int, nombre text, direccion text, referencia text, telefono text,
               lat double precision, lon double precision, cobrar int, paga_con int,
               estado text, motivo text, tiene_foto boolean, venta text, productos int)
language sql stable security definer set search_path = public as $$
  select e.id, e.orden, e.nombre, e.direccion, e.referencia, e.telefono, e.lat, e.lon,
         e.cobrar, e.paga_con, e.estado, e.motivo, e.foto is not null, e.venta, e.productos
  from entregas_salida e
  join salidas s on s.id = e.salida
  where s.codigo = p_codigo and not s.cerrada
  order by e.orden;
$$;
revoke all on function public.ver_salida(text) from public;
grant execute on function public.ver_salida(text) to anon, authenticated;
