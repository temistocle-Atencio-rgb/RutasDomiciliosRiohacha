-- DomiRuta: tablas para que el repartidor marque las entregas desde el celular.
-- Se pega completo en Supabase > SQL Editor > Run (se puede correr otra vez sin dañar nada).

create extension if not exists pgcrypto;

-- Una salida = una ruta que se mandó al repartidor por WhatsApp
create table if not exists public.salidas (
  id uuid primary key default gen_random_uuid(),
  codigo text not null unique check (length(codigo) >= 24), -- va en el enlace; es la llave secreta
  repartidor text not null default '',
  creada timestamptz not null default now(),
  cerrada boolean not null default false
);

-- Las paradas de cada salida (solo lo que el repartidor necesita)
create table if not exists public.entregas_salida (
  id bigint generated always as identity primary key,
  salida uuid not null references public.salidas(id) on delete cascade,
  orden int not null,
  nombre text not null,
  direccion text not null,
  referencia text not null default '',
  telefono text not null default '',
  lat double precision,
  lon double precision,
  cobrar int not null default 0,      -- pesos que debe cobrar en la puerta (0 = ya pagó)
  paga_con int not null default 0,    -- billete con el que paga, para llevar el cambio
  estado text not null default 'en_camino'
    check (estado in ('en_camino', 'entregado', 'no_entregado')),
  motivo text not null default '',
  actualizado timestamptz not null default now()
);
create index if not exists entregas_salida_salida_idx on public.entregas_salida (salida);

-- Con la clave pública nadie puede tocar las tablas directo: solo las dos funciones de abajo.
alter table public.salidas enable row level security;
alter table public.entregas_salida enable row level security;
revoke all on public.salidas, public.entregas_salida from anon, authenticated;

-- La página del repartidor: ver las paradas de su salida (solo con el código del enlace)
create or replace function public.ver_salida(p_codigo text)
returns table (id bigint, orden int, nombre text, direccion text, referencia text, telefono text,
               lat double precision, lon double precision, cobrar int, paga_con int,
               estado text, motivo text)
language sql stable security definer set search_path = public as $$
  select e.id, e.orden, e.nombre, e.direccion, e.referencia, e.telefono, e.lat, e.lon,
         e.cobrar, e.paga_con, e.estado, e.motivo
  from entregas_salida e
  join salidas s on s.id = e.salida
  where s.codigo = p_codigo and not s.cerrada
  order by e.orden;
$$;

-- La página del repartidor: marcar una parada (en_camino sirve para deshacer un toque por error)
create or replace function public.marcar_entrega(p_codigo text, p_id bigint, p_estado text,
                                                 p_motivo text default '')
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  if p_estado not in ('en_camino', 'entregado', 'no_entregado') then
    raise exception 'Estado no válido';
  end if;
  update entregas_salida e
     set estado = p_estado,
         motivo = case when p_estado = 'no_entregado' then left(coalesce(p_motivo, ''), 120) else '' end,
         actualizado = now()
    from salidas s
   where e.id = p_id and e.salida = s.id and s.codigo = p_codigo and not s.cerrada;
  get diagnostics n = row_count;
  return n = 1;
end;
$$;

revoke all on function public.ver_salida(text) from public;
revoke all on function public.marcar_entrega(text, bigint, text, text) from public;
grant execute on function public.ver_salida(text) to anon, authenticated;
grant execute on function public.marcar_entrega(text, bigint, text, text) to anon, authenticated;
