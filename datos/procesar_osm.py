"""
Convierte los datos crudos de OpenStreetMap (carpeta osm_crudo) en los archivos
de texto que lee el programa (carpeta datos/riohacha).

Datos crudos: se descargan de la API Overpass (https://overpass-api.de) con estas consultas,
dentro del rectangulo de Riohacha (sur, oeste, norte, este) = (11.495, -72.965, 11.585, -72.84):

  vias.json:    way["highway"~"^(trunk|primary|secondary|tertiary|unclassified|residential|
                living_street|service|road|track|..._link)$"](bbox); (._;>;); out body;
  lugares.json: nwr[name][amenity|shop|tourism|leisure|office|healthcare](bbox);
                nwr[place~"neighbourhood|suburb|quarter"](bbox);
                node[highway~"traffic_signals|stop"](bbox); node[traffic_calming](bbox);
                out center tags;
  costa.json:   way["natural"="coastline"](11.48,-72.99,11.60,-72.82); out geom;

Uso (desde la carpeta del proyecto):  python datos/procesar_osm.py
Fuente: (c) colaboradores de OpenStreetMap, licencia ODbL.
"""
import collections
import json
import os
import re

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CRUDO = os.path.join(RAIZ, 'osm_crudo')
SALIDA = os.path.join(RAIZ, 'datos', 'riohacha')
FUENTE = 'Fuente: OpenStreetMap (c) colaboradores de OpenStreetMap, licencia ODbL'

CATEGORIAS = {
    'restaurant': 'RESTAURANTE', 'fast_food': 'RESTAURANTE', 'cafe': 'RESTAURANTE', 'bar': 'RESTAURANTE',
    'ice_cream': 'RESTAURANTE', 'food_court': 'RESTAURANTE',
    'pharmacy': 'DROGUERIA', 'chemist': 'DROGUERIA',
    'hospital': 'SALUD', 'clinic': 'SALUD', 'doctors': 'SALUD', 'dentist': 'SALUD',
    'convenience': 'TIENDA', 'supermarket': 'TIENDA', 'bakery': 'TIENDA', 'alcohol': 'TIENDA', 'mall': 'TIENDA',
    'butcher': 'TIENDA', 'greengrocer': 'TIENDA', 'beverages': 'TIENDA',
    'school': 'EDUCACION', 'university': 'EDUCACION', 'college': 'EDUCACION', 'kindergarten': 'EDUCACION',
    'library': 'EDUCACION', 'bank': 'BANCO', 'atm': 'BANCO', 'fuel': 'GASOLINERA',
    'hotel': 'HOTEL', 'hostel': 'HOTEL', 'guest_house': 'HOTEL',
    'neighbourhood': 'BARRIO', 'suburb': 'BARRIO', 'quarter': 'BARRIO',
    'townhall': 'GOBIERNO', 'police': 'GOBIERNO', 'courthouse': 'GOBIERNO',
}


def leer(nombre):
    with open(os.path.join(CRUDO, nombre), encoding='utf-8') as f:
        return json.load(f)['elements']


def limpio(s):
    return (s or '').replace(';', ',').replace('|', '/').strip()


def marca_de(tags):
    if tags.get('highway') == 'traffic_signals':
        return 'S'
    if tags.get('highway') == 'stop':
        return 'P'
    if 'traffic_calming' in tags:
        return 'R'
    return None


def main():
    os.makedirs(SALIDA, exist_ok=True)
    elementos = leer('vias.json')
    nodos = {e['id']: e for e in elementos if e['type'] == 'node'}
    vias = [e for e in elementos if e['type'] == 'way']
    lugares = leer('lugares.json')

    # semaforos, pares y reductores
    marca = {}
    for e in list(nodos.values()) + [x for x in lugares if x['type'] == 'node']:
        m = marca_de(e.get('tags', {}))
        if m and (marca.get(e['id']) != 'S'):
            marca[e['id']] = m

    # se deja solo la parte conectada mas grande de la red (sin islas sueltas)
    vecinos = collections.defaultdict(set)
    for w in vias:
        for a, b in zip(w['nodes'], w['nodes'][1:]):
            vecinos[a].add(b)
            vecinos[b].add(a)
    componente, tam, c = {}, {}, 0
    for s in vecinos:
        if s in componente:
            continue
        c += 1
        pila, componente[s], tam[c] = [s], c, 0
        while pila:
            x = pila.pop()
            tam[c] += 1
            for y in vecinos[x]:
                if y not in componente:
                    componente[y] = c
                    pila.append(y)
    mayor = max(tam, key=tam.get)
    vias = [w for w in vias if componente.get(w['nodes'][0]) == mayor]

    indice, usados = {}, []
    for w in vias:
        for n in w['nodes']:
            if n not in indice:
                indice[n] = len(usados)
                usados.append(n)

    with open(os.path.join(SALIDA, 'nodos.txt'), 'w', encoding='utf-8') as f:
        f.write('# Nodos de la red vial de Riohacha. ' + FUENTE + '\n')
        f.write('# indice;lat;lon;marca (S=semaforo, P=pare, R=reductor, -=nada)\n')
        for i, n in enumerate(usados):
            f.write(f"{i};{nodos[n]['lat']:.7f};{nodos[n]['lon']:.7f};{marca.get(n, '-')}\n")

    with open(os.path.join(SALIDA, 'vias.txt'), 'w', encoding='utf-8') as f:
        f.write('# Tramos de via. ' + FUENTE + '\n')
        f.write('# nombre;tipo;sentido (0 doble, 1 una via, -1 una via al reves);superficie;velocidad max;carriles;nodos;nombre alterno\n')
        for w in vias:
            t = w['tags']
            ow = t.get('oneway', 'no')
            sentido = 1 if ow in ('yes', '1', 'true') else (-1 if ow == '-1' else 0)
            if t.get('junction') == 'roundabout' and ow != 'no':
                sentido = 1
            nombre = limpio(t.get('name') or t.get('alt_name'))
            alterno = limpio(t.get('alt_name') or t.get('old_name') or t.get('official_name'))
            if alterno == nombre:
                alterno = ''
            f.write(';'.join([nombre, t['highway'], str(sentido),
                              t.get('surface', '-'), re.sub(r'\D', '', t.get('maxspeed', '')) or '-',
                              t.get('lanes', '-'), ','.join(str(indice[n]) for n in w['nodes']), alterno]) + '\n')

    vistos, total = set(), 0
    with open(os.path.join(SALIDA, 'lugares.txt'), 'w', encoding='utf-8') as f:
        f.write('# Lugares con nombre. ' + FUENTE + '\n')
        f.write('# nombre;categoria;lat;lon\n')
        for e in lugares:
            t = e.get('tags', {})
            nombre = limpio(t.get('name'))
            if not nombre:
                continue
            if e['type'] == 'node':
                lat, lon = e['lat'], e['lon']
            elif 'center' in e:
                lat, lon = e['center']['lat'], e['center']['lon']
            else:
                continue
            clave = t.get('place') or t.get('amenity') or t.get('shop') or t.get('tourism') or t.get('healthcare') or ''
            cat = CATEGORIAS.get(clave, 'OTRO')
            if cat == 'OTRO' and t.get('leisure'):
                cat = 'RECREACION'
            if cat == 'OTRO' and t.get('tourism'):
                cat = 'TURISMO'
            k = (nombre.lower(), round(lat, 4), round(lon, 4))
            if k in vistos:
                continue
            vistos.add(k)
            total += 1
            f.write(f'{nombre};{cat};{lat:.7f};{lon:.7f}\n')

    # linea de costa: se unen los pedazos por sus extremos
    pedazos = [[(p['lat'], p['lon']) for p in e['geometry']] for e in leer('costa.json') if 'geometry' in e]
    lineas = [pedazos.pop(0)] if pedazos else []
    while pedazos:
        l, unido = lineas[-1], False
        for i, s in enumerate(pedazos):
            if s[0] == l[-1]:
                l.extend(s[1:])
            elif s[-1] == l[0]:
                lineas[-1] = s + l[1:]
            else:
                continue
            pedazos.pop(i)
            unido = True
            break
        if not unido:
            lineas.append(pedazos.pop(0))
    with open(os.path.join(SALIDA, 'costa.txt'), 'w', encoding='utf-8') as f:
        f.write('# Linea de costa (tierra a la izquierda). ' + FUENTE + '\n')
        for l in lineas:
            f.write(' '.join(f'{a:.6f},{b:.6f}' for a, b in l) + '\n')

    print(f'{len(usados)} nodos, {len(vias)} vias, {total} lugares, '
          f'{sum(1 for n in usados if marca.get(n) == "S")} semaforos')


if __name__ == '__main__':
    main()
