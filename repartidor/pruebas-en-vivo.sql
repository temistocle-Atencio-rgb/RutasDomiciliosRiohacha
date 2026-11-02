-- DomiRuta: pruebas de en-vivo.sql. Se corre DESPUÉS de en-vivo.sql, en el mismo SQL Editor.
-- Crea datos de prueba (con "PRUEBA" en el nombre), prueba todo con la clave pública (rol anon)
-- y al final los borra. El resultado es una tabla: cada fila dice BIEN o FALLA.

-- restos de una corrida anterior
delete from public.salidas where repartidor like 'PRUEBA %';
delete from public.repartidores where nombre like 'PRUEBA %';
delete from public.intentos where clave like 'codigo:%' or clave like 'dispositivo:pruebaenlace%';

drop table if exists pg_temp.resultados;
create temp table resultados (prueba text, ok boolean);
grant all on resultados to anon;

-- ---------------------------------------------------------------------
-- Datos de prueba (como los sube la caja)
-- ---------------------------------------------------------------------
insert into public.repartidores (nombre, enlace) values
  ('PRUEBA Juan', 'pruebaenlacejuan0000000000000000000'),
  ('PRUEBA Ana',  'pruebaenlaceana00000000000000000000');

insert into public.salidas (codigo, repartidor, tienda, repartidor_id, vence_en, ruta, distancia_m, tienda_lat, tienda_lon)
select 'pruebasalidajuan00000000000000', 'PRUEBA Juan', 'Tienda Prueba', id, now() + interval '10 minutes',
       '[[11.5440,-72.9070],[11.5450,-72.9060],[11.5460,-72.9050]]'::jsonb, 390, 11.5440, -72.9070
  from public.repartidores where nombre = 'PRUEBA Juan';
insert into public.salidas (codigo, repartidor, tienda, repartidor_id, vence_en)
select 'pruebasalidavencida000000000000', 'PRUEBA Juan', 'Tienda Prueba', id, now() - interval '1 minute'
  from public.repartidores where nombre = 'PRUEBA Juan';
insert into public.salidas (codigo, repartidor, tienda, repartidor_id, vence_en)
select 'pruebasalidaana0000000000000000', 'PRUEBA Ana', 'Tienda Prueba', id, now() + interval '10 minutes'
  from public.repartidores where nombre = 'PRUEBA Ana';

insert into public.entregas_salida (salida, orden, nombre, direccion, telefono, lat, lon, cobrar)
select id, 1, 'Cliente Uno', 'Calle 1 # 1-1', '300 111 2233', 11.5450, -72.9060, 5000
  from public.salidas where codigo = 'pruebasalidajuan00000000000000';
insert into public.entregas_salida (salida, orden, nombre, direccion, telefono, lat, lon, cobrar)
select id, 2, 'Cliente Dos', 'Calle 2 # 2-2', '3004445566', 11.5460, -72.9050, 0
  from public.salidas where codigo = 'pruebasalidajuan00000000000000';
insert into public.entregas_salida (salida, orden, nombre, direccion, telefono, lat, lon)
select id, 1, 'Cliente Tres', 'Calle 3 # 3-3', '3007778899', 11.5500, -72.9000
  from public.salidas where codigo = 'pruebasalidaana0000000000000000';

-- enlaces y códigos de prueba (la página los recibe por WhatsApp; aquí se leen como caja)
create temp table enlaces as
  select id, nombre, enlace_cliente, codigo_cliente, ultimos4 from public.entregas_salida where nombre like 'Cliente %';
grant select on enlaces to anon;

-- ---------------------------------------------------------------------
-- La clave pública no puede tocar tablas ni funciones internas
-- ---------------------------------------------------------------------
insert into resultados
select 'Tablas cerradas para la clave pública',
       not has_table_privilege('anon', 'public.repartidores', 'select')
   and not has_table_privilege('anon', 'public.salidas', 'select')
   and not has_table_privilege('anon', 'public.entregas_salida', 'select')
   and not has_table_privilege('anon', 'public.ubicaciones', 'select')
   and not has_table_privilege('anon', 'public.correcciones', 'select')
   and not has_table_privilege('anon', 'public.suscripciones_push', 'select')
   and not has_table_privilege('anon', 'public.intentos', 'insert');
insert into resultados
select 'Funciones internas cerradas para la clave pública',
       not has_function_privilege('anon', 'public.dr_pedido(bigint)', 'execute')
   and not has_function_privilege('anon', 'public.dr_limite(text, integer, interval)', 'execute')
   and not has_function_privilege('anon', 'public.dr_repartidor(text, text)', 'execute')
   and not has_function_privilege('anon', 'public.limpiar_viejo()', 'execute');
insert into resultados
select 'Cada entrega recibe código de 6, enlace de 24 y últimos 4 dígitos',
       bool_and(codigo_cliente ~ '^[A-HJ-NP-Z2-9]{6}$' and length(enlace_cliente) = 24)
       and bool_or(nombre = 'Cliente Uno' and ultimos4 = '2233')
  from public.entregas_salida where nombre like 'Cliente %';

-- ---------------------------------------------------------------------
-- Desde aquí todo se llama como la página (clave pública)
-- ---------------------------------------------------------------------
set role anon;

insert into resultados select 'Salida nueva: no vencida y con la tienda',
  (select not vencida and tienda = 'Tienda Prueba' and tienda_lat is not null
     from public.info_salida('pruebasalidajuan00000000000000'));
insert into resultados select 'Salida de hace 10 min: vencida',
  (select vencida from public.info_salida('pruebasalidavencida000000000000'));
insert into resultados select 'No se puede aceptar una salida vencida',
  not public.responder_salida('pruebasalidavencida000000000000', true, '');
insert into resultados select 'La ruta se puede ver antes de aceptar',
  (select jsonb_array_length(ruta) = 3 from public.ruta_salida('pruebasalidajuan00000000000000'));
insert into resultados select 'Sin aceptar no se guarda la ubicación',
  not public.enviar_posicion('pruebasalidajuan00000000000000', 11.5441, -72.9069, 10, 90, 5);
insert into resultados select 'Aceptar funciona la primera vez',
  public.responder_salida('pruebasalidajuan00000000000000', true, '');
insert into resultados select 'Aceptar otra vez no hace nada (solo uno gana)',
  not public.responder_salida('pruebasalidajuan00000000000000', true, '');
insert into resultados select 'Después de aceptar ya no se puede rechazar',
  not public.responder_salida('pruebasalidajuan00000000000000', false, 'otro');
insert into resultados select 'Con la salida aceptada se guarda la ubicación',
  public.enviar_posicion('pruebasalidajuan00000000000000', 11.5441, -72.9069, 10, 90, 5);
insert into resultados select 'Otra ubicación enseguida se ignora sin error',
  public.enviar_posicion('pruebasalidajuan00000000000000', 11.5442, -72.9068, 10, 90, 5);

reset role;
insert into resultados select 'Solo quedó una ubicación (máximo una cada 4 s)',
  (select count(*) = 1 from public.ubicaciones u join public.salidas s on s.id = u.salida
    where s.codigo = 'pruebasalidajuan00000000000000');
update public.salidas set ubicacion_en = now() - interval '10 seconds' where codigo = 'pruebasalidajuan00000000000000';
set role anon;

insert into resultados select 'Una lectura mala (error de 400 m) no mueve la moto',
  public.enviar_posicion('pruebasalidajuan00000000000000', 11.6000, -72.8000, 400, null, null);
reset role;
insert into resultados select '… pero queda en el recorrido',
  (select lat = 11.5441 from public.salidas where codigo = 'pruebasalidajuan00000000000000')
  and (select count(*) = 2 from public.ubicaciones u join public.salidas s on s.id = u.salida
        where s.codigo = 'pruebasalidajuan00000000000000');
set role anon;

-- el cliente
insert into resultados select 'Cliente Uno con su enlace: en camino, primero y con la moto',
  (select (j ->> 'estado') = 'en_camino' and (j ->> 'faltan_antes')::int = 0 and (j ->> 'moto') is not null
          and (j ->> 'tienda') = 'Tienda Prueba'
     from (select public.ver_pedido(enlace_cliente) j from enlaces where nombre = 'Cliente Uno') z);
insert into resultados select 'Cliente Dos ve "falta 1 antes" y nada del otro cliente',
  (select (j ->> 'faltan_antes')::int = 1 and j::text not like '%Uno%' and j::text not like '%2233%'
          and j::text not like '%Calle 1%' and j::text not like '%telefono%'
     from (select public.ver_pedido(enlace_cliente) j from enlaces where nombre = 'Cliente Dos') z);
insert into resultados select 'Cliente Tres (salida sin aceptar): preparando y sin moto',
  (select (j ->> 'estado') = 'preparando' and (j ->> 'moto') is null
     from (select public.ver_pedido(enlace_cliente) j from enlaces where nombre = 'Cliente Tres') z);
insert into resultados select 'Un enlace inventado no muestra nada',
  public.ver_pedido('abcdefghijkmnpqrstuvwxyz') is null;
insert into resultados select 'Código + últimos 4 correctos muestran el pedido',
  (select (public.ver_pedido_codigo(lower(codigo_cliente), '2233') ->> 'estado') = 'en_camino'
     from enlaces where nombre = 'Cliente Uno');
insert into resultados select 'Código con los 4 dígitos de otro no muestra nada',
  (select public.ver_pedido_codigo(codigo_cliente, '5566') is null from enlaces where nombre = 'Cliente Uno');

-- pausar la ubicación
insert into resultados select 'Pausar la ubicación',
  public.pausar_ubicacion('pruebasalidajuan00000000000000', true);
insert into resultados select 'Pausada: no se guarda ubicación y el cliente ve "pausada" sin moto',
  not public.enviar_posicion('pruebasalidajuan00000000000000', 11.5443, -72.9067, 10, 90, 5)
  and (select (j ->> 'ubicacion_pausada')::boolean and (j ->> 'moto') is null
         from (select public.ver_pedido(enlace_cliente) j from enlaces where nombre = 'Cliente Uno') z);
insert into resultados select 'Reanudar la ubicación', public.pausar_ubicacion('pruebasalidajuan00000000000000', false);

-- corrección de ubicación
insert into resultados select 'Corregir a más de 300 m no se acepta',
  (select public.corregir_ubicacion('pruebasalidajuan00000000000000', id, 11.5500, -72.9000, null, null) is null
     from enlaces where nombre = 'Cliente Uno');
insert into resultados select 'Corrección que coincide con el GPS: se aplica sola',
  (select public.corregir_ubicacion('pruebasalidajuan00000000000000', id, 11.54505, -72.90605, 11.54507, -72.90603) = 'aplicada'
     from enlaces where nombre = 'Cliente Uno');
insert into resultados select 'Corrección sin GPS: queda para revisar',
  (select public.corregir_ubicacion('pruebasalidajuan00000000000000', id, 11.54520, -72.90610, null, null) = 'pendiente'
     from enlaces where nombre = 'Cliente Uno');
insert into resultados select 'Corrección de una entrega de otra salida: no se acepta',
  (select public.corregir_ubicacion('pruebasalidajuan00000000000000', id, 11.5500, -72.9000, null, null) is null
     from enlaces where nombre = 'Cliente Tres');

-- entregar
insert into resultados select 'Marcar entregado con el GPS',
  (select public.marcar_entrega_gps('pruebasalidajuan00000000000000', id, 'entregado', '', 11.54506, -72.90604)
     from enlaces where nombre = 'Cliente Uno');
insert into resultados select 'Entregado: el cliente ve la hora y ya no la moto',
  (select (j ->> 'estado') = 'entregado' and j ->> 'entregada_en' is not null and (j ->> 'moto') is null
     from (select public.ver_pedido(enlace_cliente) j from enlaces where nombre = 'Cliente Uno') z);
insert into resultados select 'Cliente Dos ahora es el siguiente (faltan 0)',
  (select (j ->> 'faltan_antes')::int = 0
     from (select public.ver_pedido(enlace_cliente) j from enlaces where nombre = 'Cliente Dos') z);

-- app del repartidor (enlace personal)
insert into resultados select 'Enlace personal: el primer celular queda amarrado y ve su salida',
  (select (j ->> 'otro_dispositivo')::boolean = false and json_array_length(j -> 'salidas') = 2
          and j::text like '%pruebasalidajuan%' and j::text not like '%pruebasalidaana%'
     from (select public.mis_salidas('pruebaenlacejuan0000000000000000000', 'celular-de-juan-aaaa') j) z);
insert into resultados select 'Abierto en otro celular: pide confirmar y no muestra salidas',
  (select (j ->> 'otro_dispositivo')::boolean and (j ->> 'salidas') is null
     from (select public.mis_salidas('pruebaenlacejuan0000000000000000000', 'otro-celular-bbbbbbb') j) z);
insert into resultados select 'Ana solo ve sus salidas',
  (select j::text like '%pruebasalidaana%' and j::text not like '%pruebasalidajuan%'
     from (select public.mis_salidas('pruebaenlaceana00000000000000000000', 'celular-de-ana-cccc') j) z);
insert into resultados select 'Enlace inventado o celular inválido: nada',
  public.mis_salidas('enlaceinventado0000000000000000000', 'celular-de-juan-aaaa') is null
  and public.mis_salidas('pruebaenlacejuan0000000000000000000', 'corto') is null;
insert into resultados select 'Disponible solo desde su celular',
  not public.cambiar_disponible('pruebaenlacejuan0000000000000000000', 'otro-celular-bbbbbbb', true)
  and public.cambiar_disponible('pruebaenlacejuan0000000000000000000', 'celular-de-juan-aaaa', true);
insert into resultados select 'Notificaciones: rechaza direcciones raras y guarda la buena',
  not public.guardar_suscripcion('pruebaenlacejuan0000000000000000000', 'celular-de-juan-aaaa', 'http://x.com/a', 'k', 'a')
  and public.guardar_suscripcion('pruebaenlacejuan0000000000000000000', 'celular-de-juan-aaaa',
                                 'https://fcm.googleapis.com/fcm/send/prueba', 'clave', 'auth');
insert into resultados select 'Confirmar el celular nuevo pasa el enlace y el viejo queda fuera',
  public.confirmar_dispositivo('pruebaenlacejuan0000000000000000000', 'otro-celular-bbbbbbb')
  and (select (public.mis_salidas('pruebaenlacejuan0000000000000000000', 'celular-de-juan-aaaa') ->> 'otro_dispositivo')::boolean);

-- límite de intentos con el código corto (al final, porque bloquea por 10 minutos)
insert into resultados
select 'Tras 10 intentos fallidos con código, pide esperar',
  (select bool_and(public.ver_pedido_codigo('ZZZZZZ', '0000') is null) from generate_series(1, 9))
  and (public.ver_pedido_codigo('ZZZZZZ', '0000') ->> 'error') = 'espera';

reset role;

-- La caja sí ve todo con la clave secreta
insert into resultados select 'La caja (clave secreta) puede leer y escribir las tablas',
  has_table_privilege('service_role', 'public.repartidores', 'insert')
  and has_table_privilege('service_role', 'public.ubicaciones', 'select')
  and has_table_privilege('service_role', 'public.correcciones', 'update');
insert into resultados select 'La caja ve el aviso de "otro celular"',
  (select dispositivo_cambio is not null from public.repartidores where nombre = 'PRUEBA Juan');

-- limpieza
delete from public.salidas where repartidor like 'PRUEBA %';
delete from public.repartidores where nombre like 'PRUEBA %';
delete from public.intentos where clave like 'codigo:%' or clave like 'dispositivo:pruebaenlace%';

select row_number() over () as n, prueba, case when ok then 'BIEN' else 'FALLA' end as resultado
  from resultados
union all
select null, 'TOTAL: ' || count(*) filter (where ok) || ' bien de ' || count(*), '' from resultados;
