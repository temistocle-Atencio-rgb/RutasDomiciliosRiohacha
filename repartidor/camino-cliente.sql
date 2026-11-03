-- DomiRuta: el cliente ve la línea de lo que le falta a la moto para llegar a su casa.
-- Solo cuando él es la siguiente entrega (así la línea nunca pasa por las casas de otros clientes).
-- Se corre DESPUÉS de en-vivo.sql. Se puede correr otra vez sin dañar nada.

-- El tramo de la ruta desde el punto más cercano a la moto hasta el más cercano a la casa (null si la moto no va por la ruta)
create or replace function public.dr_tramo(p_ruta jsonb, p_lat double precision, p_lon double precision,
                                           p_casa_lat double precision, p_casa_lon double precision)
returns jsonb
language sql immutable set search_path = public as $$
  with pts as (
    select (e.ordinality - 1)::int as i, (e.value->>0)::double precision as lat, (e.value->>1)::double precision as lon
    from jsonb_array_elements(case when jsonb_typeof(p_ruta) = 'array' then p_ruta else '[]'::jsonb end) with ordinality e
  ),
  d as (
    select i, lat, lon,
           111320 * sqrt((lat - p_lat)^2 + ((lon - p_lon) * cos(radians(p_lat)))^2) as a_moto,
           111320 * sqrt((lat - p_casa_lat)^2 + ((lon - p_casa_lon) * cos(radians(p_casa_lat)))^2) as a_casa
    from pts
  ),
  desde as (select i, a_moto from d order by a_moto limit 1),
  hasta as (select d.i from d, desde where d.i >= desde.i order by d.a_casa limit 1)
  select case when (select a_moto from desde) > 80 or (select count(*) from pts) < 2 then null
              else (select jsonb_agg(jsonb_build_array(lat, lon) order by i) from d
                    where i between (select i from desde) and (select i from hasta)) end;
$$;
revoke all on function public.dr_tramo(jsonb, double precision, double precision, double precision, double precision)
  from public, anon, authenticated;

create or replace function public.dr_pedido(p_id bigint)
returns json
language plpgsql stable security definer set search_path = public as $$
declare
  e entregas_salida%rowtype;
  s salidas%rowtype;
  v_estado text;
  v_moto json;
  v_faltan int := 0;
  v_camino jsonb;
begin
  select * into e from entregas_salida where id = p_id;
  if not found then
    return null;
  end if;
  select * into s from salidas where id = e.salida;
  if s.creada < now() - interval '7 days' then
    return null; -- enlaces muy viejos ya no muestran nada
  end if;
  v_estado := case
    when e.estado = 'entregado' then 'entregado'
    when e.estado = 'no_entregado' then 'no_entregado'
    when s.aceptada is null or s.rechazada is not null then 'preparando'
    else 'en_camino' end;
  if v_estado = 'en_camino' then
    select count(*) into v_faltan from entregas_salida o
     where o.salida = s.id and o.estado = 'en_camino' and o.orden < e.orden;
  end if;
  -- la moto solo se ve mientras va hacia este cliente, con ubicación reciente y sin pausar
  if v_estado = 'en_camino' and not s.cerrada and not s.ubicacion_pausada
     and s.ubicacion_en > now() - interval '5 minutes' and s.lat is not null then
    v_moto := json_build_object('lat', s.lat, 'lon', s.lon, 'rumbo', s.rumbo, 'hora', s.ubicacion_en);
    -- la línea de lo que falta: solo si es el siguiente
    if v_faltan = 0 and e.lat is not null and s.ruta is not null then
      v_camino := dr_tramo(s.ruta::jsonb, s.lat, s.lon, e.lat, e.lon);
    end if;
  end if;
  return json_build_object(
    'tienda', s.tienda,
    'nombre', split_part(trim(e.nombre), ' ', 1),
    'estado', v_estado,
    'faltan_antes', v_faltan,
    'repartidor', split_part(trim(coalesce(s.repartidor, '')), ' ', 1),
    'destino', case when e.lat is not null then json_build_object('lat', e.lat, 'lon', e.lon) end,
    'moto', v_moto,
    'camino', v_camino,
    'ubicacion_pausada', v_estado = 'en_camino' and s.ubicacion_pausada,
    'entregada_en', e.entregada_en);
end;
$$;
revoke all on function public.dr_pedido(bigint) from public, anon, authenticated;
