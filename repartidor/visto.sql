-- DomiRuta: la página del repartidor ve si la tienda ya leyó sus mensajes (doble chulo azul).
drop function if exists public.ver_mensajes(text, bigint);
create function public.ver_mensajes(p_codigo text, p_desde bigint default 0)
returns table (id bigint, de text, texto text, creado timestamptz, leido boolean)
language sql stable security definer set search_path = public as $$
  select m.id, m.de, m.texto, m.creado, m.leido
  from mensajes_salida m
  where m.salida = salida_abierta(p_codigo) and m.id > coalesce(p_desde, 0)
  order by m.id;
$$;
revoke all on function public.ver_mensajes(text, bigint) from public;
grant execute on function public.ver_mensajes(text, bigint) to anon, authenticated;
