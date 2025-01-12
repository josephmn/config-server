# config-server

### Antes: version = 1.0.0-SNAPSHOT

### mvn release:prepare
1. Cambia a version = 1.0.0
2. Crea tag v1.0.0
3. Cambia a version = 1.0.1-SNAPSHOT

### mvn release:perform
1. Construye desde el tag v1.0.0
2. Despliega los artefactos al repositorio Maven

---
### mvn release:prepare:

- Verifica que no hay cambios sin commitear
- Actualiza la versión del proyecto quitando el "-SNAPSHOT"
- Ejecuta las pruebas del proyecto
- Crea un commit con la nueva versión
- Crea un tag en Git con esa versión
- Incrementa la versión para el próximo desarrollo (añadiendo -SNAPSHOT)
- Crea otro commit con esta versión de desarrollo

### mvn release:perform:

- Hace checkout del tag creado por prepare
- Construye el proyecto desde ese tag
- Despliega los artefactos construidos al repositorio de Maven configurado
- NO publica automáticamente en GitHub Releases