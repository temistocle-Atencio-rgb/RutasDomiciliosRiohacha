# DomiRuta: optimizador de rutas de entrega para domicilios en La Guajira

Proyecto final de Estructuras de Datos II, Universidad de La Guajira.
Corresponde al proyecto 2 de la guía: **Optimizador de Rutas de Entrega para Domicilios**.

Programa en Java con Swing que calcula en qué orden y por qué calles debe ir un
repartidor para hacer varias entregas en Riohacha. Usa la red vial real de la ciudad
(calles, carreras, sentidos de las vías, semáforos y vías destapadas) sacada de
OpenStreetMap, un grafo con Dijkstra para los caminos y árboles AVL para buscar
direcciones, calles, lugares y clientes.

## Qué hace

- **Mapa real de Riohacha**: 10.901 nodos, 25.650 aristas, 2.292 tramos de vía,
  1.468 lugares con nombre (barrios, restaurantes, droguerías, colegios, bancos...),
  17 semáforos y 17 pares. Se mueve arrastrando y se acerca con la rueda del mouse.
- **Direcciones colombianas**: entiende `Calle 15 # 10-20`, `Cra 7 No. 12-30`,
  `Carrera 7 con Calle 15`, `Cl 14E 25-15` o el nombre de un lugar (`Uniguajira`).
  La dirección puede venir con más texto (`La Ancha, centro, calle 7# 10 - 02`) y
  también entiende nombres populares de las vías (`La Ancha` = Calle 7, `La Primera` = Calle 1),
  que se configuran en `datos/alias.txt`.
  "Calle 15 # 10-20" se ubica sobre la Calle 15, a 20 m de la esquina con la Carrera 10.
- **Varias entregas**: escoge el mejor orden y arma la ruta completa por las calles,
  respetando las vías de una sola dirección. Se puede volver a la base al terminar.
- **Varios repartidores**: reparte las entregas entre varias motos por zonas y equilibra
  el tiempo para que todas terminen más o menos a la vez. Cada moto tiene su color en el mapa.
- **Entregas urgentes**: las marcadas como urgentes se hacen antes que las demás.
- **Pegar lista de pedidos**: se pegan varios pedidos de una vez (uno por línea, como llegan
  por WhatsApp) con el nombre del cliente antes de la dirección.
- **Direcciones que no aparecen**: se marcan una vez en el mapa y el programa las recuerda.
- **Nombres de calles deducidos**: muchos tramos no tienen nombre en OpenStreetMap; el
  programa los deduce por continuidad y contando cuadras (ver más abajo).
- **Otros municipios**: Maicao, Uribia, Manaure, Fonseca, San Juan del Cesar y los demás
  municipios de La Guajira. El mapa se descarga de OpenStreetMap desde el mismo programa.
- **Más rápida o más corta**: por tiempo (con tráfico) o por distancia.
- **Tráfico**: velocidad según el tipo de vía, vías destapadas, espera en semáforos,
  pares y reductores, hora pico (más fuerte en vías principales y en el centro).
- **Novedades**: se reportan trancones, huecos, obras o vías cerradas haciendo clic
  en la calle. La ruta más rápida las evita.
- **Indicaciones paso a paso** ("Gire a la derecha en Calle 15 y siga 300 m") y hora
  estimada de llegada a cada entrega.
- **Compartir**: abre la ruta en Google Maps para navegar en el celular o la copia
  como texto para mandarla por WhatsApp al repartidor.
- **Clientes frecuentes** guardados con su dirección.
- **Historial** de rutas en archivo de texto, se pueden ver otra vez y repetir.
- **Matriz de adyacencia** entre las paradas de la ruta, para ver los costos.

## Estructuras de datos

| Estructura | Clase | Uso en el proyecto |
|---|---|---|
| Grafo dirigido ponderado con **lista de adyacencia** (listas enlazadas de aristas) | `Grafo`, `Arista` | Red vial de la ciudad: nodos = esquinas y puntos de las calles, aristas = tramos con su distancia en metros. Las calles de doble vía tienen una arista en cada sentido |
| **Matriz de adyacencia** | `MatrizAdyacencia` | Costo del mejor camino entre cada par de paradas de la ruta (base + entregas). No es simétrica por las calles de una vía |
| **Dijkstra** con montículo | `Dijkstra` | Camino más corto o más rápido desde una parada a todos los nodos. O((V + E) log V) |
| **Montículo de mínimos** (cola de prioridad) | `MonticuloMin` | Saca el nodo más cercano en Dijkstra en O(log n) |
| **Árbol AVL** | `ArbolAVL`, `NodoAVL` | Índice de calles por nombre, números de calles y carreras (con `piso` y `techo`), buscador de lugares por palabra, clientes ordenados por nombre |
| **BFS** con cola enlazada | `Grafo.bfs`, `Cola` | Saber a qué zonas se puede llegar desde la base respetando el sentido de las calles |
| Backtracking con poda | `PlanificadorRutas` | Orden óptimo de hasta 9 entregas (con las urgentes primero) |
| BFS sobre tramos de vía | `InferenciaNombres` | Propagar nombres de calles a los tramos vecinos que siguen en línea recta |
| BFS inverso | `Grafo.bfsInverso`, `Grafo.idaYVuelta` | Zonas a las que se puede ir **y volver** desde la base |
| AVL de direcciones corregidas | `Geocodificador` | Recordar direcciones marcadas a mano |
| Analizador recursivo descendente | `osm/Json` | Leer el JSON de OpenStreetMap sin librerías externas |

### ¿Por qué lista de adyacencia para la ciudad y matriz para las paradas?

La red vial tiene casi 11.000 nodos y cada esquina se conecta con 2 a 4 vecinos.
Una matriz de 11.000 × 11.000 tendría 121 millones de celdas (cerca de 1 GB) casi
todas vacías, por eso la ciudad va en lista de adyacencia. Entre las paradas de una
ruta (10 o 20 puntos) todas se conectan con todas, y ahí sí conviene la matriz de
adyacencia: el planificador la consulta miles de veces para probar órdenes.

### Cómo se escoge el orden de las entregas

1. Se corre Dijkstra desde cada parada y se llena la matriz de adyacencia.
2. Encontrar el orden óptimo es el problema del agente viajero (NP-difícil):
   - **Hasta 9 entregas**: búsqueda exacta con backtracking y poda. Se prueban los
     órdenes posibles pero se corta cualquier camino que ya cueste más que el mejor encontrado.
   - **Más de 9**: heurística del vecino más cercano y después mejora **2-opt**
     (invertir pedazos del recorrido mientras baje el costo).
3. Se unen los caminos de Dijkstra en ese orden.

### Varios repartidores

1. **Barrido**: las entregas se ordenan por el ángulo que forman con la base (como las
   agujas de un reloj) y se parten en grupos seguidos; así cada moto toma una zona.
   Se prueba empezar el barrido en cada entrega y se queda la partición cuya ruta más
   larga sea la más corta.
2. **Mejora**: mientras se pueda, se pasa una entrega de la ruta más larga a otra moto si
   con eso baja el tiempo de la ruta más larga.
3. Cada grupo se ordena con la búsqueda exacta o con 2-opt.

En las pruebas, 20 entregas reales con 3 motos terminan en 37 minutos; con una sola moto,
1 h 42 min.

### Nombres de calles deducidos

En OpenStreetMap una calle larga está partida en muchos tramos y no todos tienen nombre.

- **Continuidad**: si un tramo sin nombre sigue en línea recta a uno que se llama
  "Calle 15", también es la Calle 15. El nombre se propaga con un BFS sobre los tramos.
- **Conteo de cuadras**: desde un tramo sin nombre se traza una línea perpendicular y se
  cuentan las paralelas que corta. Si a un lado está la Calle 10, al otro la Calle 13 y en
  medio hay exactamente dos paralelas, son la 11 y la 12. Si hay calles con letra (11A) el
  conteo no cuadra y no se adivina.

Solo se aplica a calles rectas de la ciudad (no a trochas ni carreteras con curvas).

### Cómo se ubica una dirección

`Calle 15 # 10-20` se busca así:

1. En el AVL de calles se buscan los tramos que se llaman "Calle 15".
2. Se busca la esquina donde la Calle 15 toca la Carrera 10 (los nodos más cercanos
   entre las dos vías).
3. Si la Carrera 10 no tiene nombre en el mapa, se buscan en el AVL de números con
   `piso()` y `techo()` las carreras vecinas que sí están (por ejemplo la 9 y la 11) y se
   interpola entre esas dos esquinas.
4. Se avanzan 20 m hacia la siguiente esquina y se pega el punto al nodo más cercano
   de la Calle 15 al que se pueda llegar desde la base.

## Modelo de tiempo

El tiempo de cada arista es `metros / velocidad + espera en la esquina`. Los valores
están en `datos/trafico.txt` y se pueden ajustar con recorridos reales sin tocar el código:

- Velocidad por tipo de vía (troncal 45 km/h, secundaria 36, calle de barrio 24...).
- Vías destapadas: velocidad × 0,55.
- Semáforo: 25 s de espera (50 s en hora pico). Pare: 6 s. Reductor: 4 s.
- Hora pico (6:30-8:30, 11:30-14:00, 17:00-19:30): vías principales × 0,55,
  calles de barrio × 0,85 y el centro (900 m alrededor del Parque Padilla) × 0,75 adicional.
- Novedades reportadas: trancón × 0,35, huecos × 0,5, obra × 0,4, vía cerrada no se usa.
- 3 minutos por entrega.

Son estimaciones: no hay datos públicos de tráfico en vivo para Riohacha. Para un
negocio real conviene medir algunos recorridos y ajustar `trafico.txt`.

## Cómo ejecutarlo

Requiere Java 17 o superior.

**En NetBeans:** File > Open Project, escoger la carpeta `RutasDomiciliosRiohacha`
y darle Run. La clase principal es `rutas.Main`.

**Pruebas:** clic derecho sobre `Pruebas.java` > Run File. Prueban el AVL, el montículo,
Dijkstra en un grafo pequeño y el mapa real (direcciones, BFS, rutas, hora pico).

**Por consola** (desde la carpeta del proyecto):

```
mvn package
java -jar target/RutasDomiciliosRiohacha-2.0.jar
```

El programa se ejecuta desde la carpeta del proyecto porque lee la carpeta `datos`.

### Versión para entregar a un cliente

`empaque\empaquetar.bat` (Windows) o `empaque/empaquetar.sh` (Linux/macOS) compila,
arma `DomiRuta.jar` y con `jpackage` crea la carpeta `dist/DomiRuta` con **Java incluido**
y un `DomiRuta.exe` con el ícono del cubo, más un `.zip` listo para enviar. El cliente
solo descomprime y abre `DomiRuta.exe`; no tiene que instalar Java. Pesa unos 87 MB
(Java recortado a los módulos que usa el programa).

## Archivos de datos

Datos que vienen con el programa (solo lectura):

```
datos/
  ciudades.txt            municipios de La Guajira: centro y radio del área urbana
  trafico.txt             parámetros del modelo de tráfico
  riohacha/nodos.txt      nodos de la red vial (lat, lon, semáforo/pare)
  riohacha/vias.txt       tramos: nombre, tipo, sentido, superficie, velocidad, nodos, nombre alterno
  riohacha/lugares.txt    lugares con nombre y categoría
  riohacha/costa.txt      línea de costa para dibujar el mar
  riohacha/alias.txt      nombres populares de las vías (la ancha=Calle 7)
```

Datos de cada usuario: en Windows `%APPDATA%\DomiRuta` (en Linux/macOS `~/.domiruta`),
una carpeta por ciudad. Así el programa funciona aunque se instale en "Archivos de
programa" y cada usuario conserva sus datos al actualizar:

```
DomiRuta/
  ciudad.txt                         última ciudad usada
  errores.log                        errores inesperados (para dar soporte)
  mapas/<ciudad>/                    mapas descargados o actualizados desde el programa
  riohacha/base.txt                  punto de salida
  riohacha/clientes.txt              clientes frecuentes
  riohacha/novedades.txt             trancones, huecos, obras, vías cerradas
  riohacha/historial_rutas.txt       rutas guardadas
  riohacha/direcciones_corregidas.txt  direcciones marcadas a mano en el mapa
```

En la pestaña Historial está el botón "Abrir carpeta" para verlos.

## Estructura del código

```
src/main/java/rutas/
  Main.java, Pruebas.java
  estructuras/  ArbolAVL, NodoAVL, Grafo, Arista, MatrizAdyacencia, MonticuloMin, Cola
  modelo/       Via, Lugar, Parada, Ruta, Cliente, Ciudad, Incidente, Indicacion, Geo...
  logica/       CargadorMapa, Dijkstra, PlanificadorRutas, ModeloTrafico, Geocodificador,
                InferenciaNombres, BuscadorLugares, GeneradorIndicaciones, ArchivosDatos, Ubicaciones
  osm/          Json, ProcesadorOSM, DescargadorOSM (descarga de mapas de OpenStreetMap)
  vista/        VentanaPrincipal, PanelMapa, PanelPedidos, PanelExplorar, PanelClientes,
                PanelNovedades, PanelHistorial, DialogoDescarga, Tema, Logo
src/main/resources/fuentes/   Poppins e Inter (licencia SIL OFL)
empaque/                      scripts para armar la versión con Java incluido, ícono y LEEME
```

## Limitaciones

- La calidad depende de OpenStreetMap. En el casco urbano de Riohacha casi todas las calles
  tienen nombre; en barrios nuevos de la periferia muchas no, y ahí la dirección se marca
  una vez en el mapa. Mejorar OpenStreetMap (es libre) mejora el programa.
- Algunas cuadras no tienen nodos intermedios, así que la entrega se asigna a la esquina
  más cercana (el punto exacto se conserva para Google Maps).
- El tráfico es un modelo con valores promedio, no información en vivo.
- La hora de llegada usa el tráfico de la hora de salida para toda la ruta.

## Modelo de negocio

- Clientes: restaurantes, droguerías, tiendas y empresas de mensajería de Riohacha.
- Venta del software ($800.000) más soporte, o licencia mensual ($120.000).
- Ya funciona en los 15 municipios de La Guajira; otra ciudad del país se agrega con una
  línea en `datos/ciudades.txt`.
- Plan por número de motos: el cálculo para varios repartidores es la función que más
  valor le da a una empresa de mensajería.

## Créditos

- Datos del mapa: © colaboradores de OpenStreetMap, disponibles bajo la licencia
  Open Database License (ODbL). https://www.openstreetmap.org/copyright
- Fuentes Poppins e Inter: SIL Open Font License (archivos en `src/main/resources/fuentes`).
- Identidad visual basada en el manual de marca del grupo.
