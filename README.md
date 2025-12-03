```
src/
├── core/                           # ⭐ NÚCLEO DEL NEGOCIO
│   │                               # Contiene la lógica de negocio pura
│   │                               # NO depende de frameworks (Express, Sequelize, etc.)
│   │                               # Fácil de testear sin BD ni API externa
│   │
│   ├── use-cases/                  # CASOS DE USO (Orquestación)
│   │   │                           # Cada archivo = 1 acción que puede hacer el usuario
│   │   │                           # Orquesta llamadas a repositorios, servicios, etc.
│   │   │
│   │   ├── CreateAdmision.js       # Crea una nueva admisión
│   │   │   // Ejemplo:
│   │   │   // 1. Validar datos
│   │   │   // 2. Generar código si no viene
│   │   │   // 3. Guardar en BD (via repository)
│   │   │   // 4. Generar etiqueta (via adapter)
│   │   │   // 5. Retornar resultado
│   │   │
│   │   └── ProcessCourierOrder.js  # Envía orden al courier
│   │       // Ejemplo:
│   │       // 1. Obtener admisión de BD
│   │       // 2. Determinar qué courier usar
│   │       // 3. Adaptar datos al formato del courier
│   │       // 4. Enviar a API del courier
│   │       // 5. Actualizar estado en BD
│   │
│   └── domain/                     # DOMINIO (Reglas de negocio)
│       └── services/               # Servicios de dominio puro
│           │                       # Lógica que NO requiere BD ni APIs
│           │
│           ├── TrackingGenerator.js    # Genera números de tracking
│           │   // Ejemplo:
│           │   // - Algoritmo para generar códigos únicos
│           │   // - Validar formato de tracking
│           │   // - Checksum, validaciones, etc.
│           │
│           ├── AddressParser.js        # Parsea y normaliza direcciones
│           │   // Ejemplo:
│           │   // - Extraer calle, número, comuna
│           │   // - Normalizar formato
│           │   // - Validar dirección completa
│           │
│           └── PriceCalculator.js      # Calcula precios de envío
│               // Ejemplo:
│               // - Peso, volumen → precio
│               // - Aplicar descuentos
│               // - Reglas de negocio de pricing
│
├── adapters/                       # ⭐ ADAPTADORES (Patrón Adapter)
│   │                               # Traducen entre tu sistema y sistemas externos
│   │                               # Implementan interfaces definidas en core/
│   │
│   ├── couriers/                   # Adaptadores para couriers
│   │   │
│   │   ├── CourierAdapter.js       # INTERFAZ base que todos deben cumplir
│   │   │   // Ejemplo:
│   │   │   // class CourierAdapter {
│   │   │   //   async createOrder(data) {}
│   │   │   //   async getTracking(id) {}
│   │   │   //   async cancelOrder(id) {}
│   │   │   // }
│   │   │
│   │   ├── HomedeliveryAdapter.js  # Implementación para Homedelivery
│   │   │   // Ejemplo:
│   │   │   // - Transforma tu JSON → JSON de Homedelivery
│   │   │   // - Llama a la API de Homedelivery
│   │   │   // - Transforma respuesta de Homedelivery → tu formato
│   │   │
│   │   └── IFlowAdapter.js         # Implementación para iFlow
│   │       // Ejemplo:
│   │       // - Transforma tu JSON → JSON de iFlow
│   │       // - Llama a la API de iFlow
│   │       // - Transforma respuesta de iFlow → tu formato
│   │
│   └── label-generators/           # Adaptadores para generadores de etiquetas
│       │
│       ├── LabelAdapter.js         # INTERFAZ base
│       │   // Ejemplo:
│       │   // class LabelAdapter {
│       │   //   async generate(data) {}
│       │   //   getFormat() {} // 'ZPL', 'EPL', 'PDF'
│       │   // }
│       │
│       └── implementations/
│           ├── ZPLAdapter.js       # Implementación para ZPL
│           │   // Ejemplo:
│           │   // - Toma datos normalizados
│           │   // - Genera código ZPL
│           │   // - Retorna base64
│           │
│           ├── EPLAdapter.js       # Implementación para EPL
│           └── PDFAdapter.js       # Implementación para PDF
│
├── infrastructure/                 # ⭐ INFRAESTRUCTURA (Detalles técnicos)
│   │                               # Todo lo relacionado con tecnologías específicas
│   │                               # (Base de datos, APIs externas, etc.)
│   │
│   ├── database/                   # Todo lo relacionado con la BD
│   │   │
│   │   ├── models/                 # MODELOS de Sequelize
│   │   │   │                       # Define cómo se guarda en PostgreSQL
│   │   │   │
│   │   │   ├── admisionPedido.js   # Tabla admision_pedidos
│   │   │   ├── bulto.js            # Tabla bultos
│   │   │   ├── contenidoBulto.js   # Tabla contenido_bulto
│   │   │   └── etiqueta.js         # Tabla etiquetas
│   │   │
│   │   └── repositories/           # REPOSITORIOS (Patrón Repository)
│   │       │                       # Encapsulan acceso a la BD
│   │       │                       # Use case NO conoce Sequelize, solo Repository
│   │       │
│   │       ├── admision.repository.js
│   │       │   // Ejemplo:
│   │       │   // async save(admision) {
│   │       │   //   return await AdmisionPedido.create(admision);
│   │       │   // }
│   │       │   // async findById(id) { ... }
│   │       │   // async findPending() { ... }
│   │       │
│   │       ├── bulto.repository.js
│   │       └── etiqueta.repository.js
│   │
│   └── external/                   # Clientes para APIs externas
│       └── apis/
│           ├── IFlowClient.js      # Cliente HTTP para iFlow
│           │   // Ejemplo:
│           │   // - Maneja autenticación
│           │   // - Reintentos automáticos
│           │   // - Logging de requests
│           │   // - Manejo de errores HTTP
│           │
│           └── HomedeliveryClient.js
│
├── application/                    # ⭐ CAPA DE APLICACIÓN
│   │                               # Orquesta casos de uso
│   │                               # Coordina entre capas
│   │
│   ├── services/                   # SERVICIOS de aplicación
│   │   │                           # Coordinan múltiples use-cases
│   │   │                           # Manejan transacciones
│   │   │
│   │   ├── AdmisionService.js      # TU SERVICIO ACTUAL
│   │   │   // Ejemplo actualizado:
│   │   │   // async procesarAdmision(json) {
│   │   │   //   return await this.createAdmisionUseCase.execute(json);
│   │   │   // }
│   │   │
│   │   └── ClienteService.js
│   │
│   └── dto/                        # ⭐ DATA TRANSFER OBJECTS
│       │                           # Transforman datos entre capas
│       │                           # Request → Dominio → Response
│       │
│       ├── AdmisionDTO.js          # DTO para admisiones
│       │   // Ejemplo:
│       │   // static fromRequest(json) {
│       │   //   // JSON del request → objeto de dominio
│       │   //   return {
│       │   //     codCliente: json.cod_cliente,
│       │   //     formatoEtiqueta: json.formato_etiqueta,
│       │   //     // ... snake_case → camelCase
│       │   //   };
│       │   // }
│       │   // 
│       │   // static toResponse(admision, etiqueta) {
│       │   //   // Objeto de dominio → JSON del response
│       │   //   return {
│       │   //     estado: 'OK',
│       │   //     admision: { ... },
│       │   //     etiqueta: etiqueta
│       │   //   };
│       │   // }
│       │
│       └── BultoDTO.js
│
└── presentation/                   # ⭐ CAPA DE PRESENTACIÓN
    │                               # Todo lo que "entra" al sistema
    │                               # HTTP, Jobs, WebSockets, etc.
    │
    ├── http/                       # Todo lo relacionado con HTTP/REST
    │   │
    │   ├── controllers/            # CONTROLADORES (Reciben requests)
    │   │   │                       # - Parsean request
    │   │   │                       # - Llaman al service
    │   │   │                       # - Formatean response
    │   │   │
    │   │   ├── admision.controller.js
    │   │   │   // Ejemplo:
    │   │   │   // async procesarAdmision(req, res) {
    │   │   │   //   try {
    │   │   │   //     const dto = AdmisionDTO.fromRequest(req.body);
    │   │   │   //     const result = await admisionService.create(dto);
    │   │   │   //     const response = AdmisionDTO.toResponse(result);
    │   │   │   //     res.json(response);
    │   │   │   //   } catch (error) {
    │   │   │   //     res.status(500).json({ error });
    │   │   │   //   }
    │   │   │   // }
    │   │   │
    │   │   └── cliente.controller.js
    │   │
    │   ├── routes/                 # RUTAS de Express
    │   │   │                       # Definen endpoints
    │   │   │
    │   │   ├── admision.routes.js  # POST /admision, GET /admision/:id
    │   │   └── cliente.routes.js
    │   │
    │   └── validators/             # VALIDADORES de request
    │       │                       # Validan que el JSON sea correcto
    │       │
    │       └── admision.schema.js  # TU VALIDADOR ACTUAL con Joi
    │
    └── jobs/                       # JOBS/CRON de background
        │                           # Tareas programadas
        │
        ├── ProcessAdmissions.job.js
        │   // Ejemplo:
        │   // - Cada minuto busca admisiones pendientes
        │   // - Llama a ProcessCourierOrderUseCase
        │   // - Actualiza estados
        │
        └── NightlyCleanup.job.js
```

## 🔄 Flujo de Datos Completo

### Ejemplo: POST /admision

```
1. HTTP Request llega
   ↓
2. routes/admision.routes.js → admision.controller.js
   ↓
3. validators/admision.schema.js valida el JSON
   ↓
4. controller convierte request → DTO (AdmisionDTO.fromRequest)
   ↓
5. controller llama → application/services/AdmisionService
   ↓
6. service llama → core/use-cases/CreateAdmision
   ↓
7. use-case usa:
   - infrastructure/database/repositories → guardar en BD
   - adapters/label-generators → generar etiqueta
   - core/domain/services → lógica de negocio
   ↓
8. use-case retorna resultado al service
   ↓
9. service retorna al controller
   ↓
10. controller convierte resultado → DTO (AdmisionDTO.toResponse)
   ↓
11. HTTP Response al cliente
```

## ✅ Ventajas de Esta Arquitectura

1. **Testeable**: Cada capa se puede testear aisladamente
2. **Mantenible**: Cambios en una capa no afectan otras
3. **Escalable**: Fácil agregar nuevos couriers, formatos, etc.
4. **Desacoplado**: No dependes de frameworks específicos
5. **Claro**: Cada carpeta tiene una responsabilidad única

## 🎯 Reglas de Dependencia

```
presentation → application → core ← infrastructure
                                ↑
                            adapters
```

- ✅ presentation puede depender de application
- ✅ application puede depender de core
- ✅ infrastructure implementa interfaces de core
- ✅ adapters implementan interfaces de core
- ❌ core NO puede depender de nada externo
- ❌ domain NO puede usar Sequelize, Express, etc.

## 📝 Ejemplo Real: Agregar Nuevo Courier (FedEx)

1. Crear `adapters/couriers/FedExAdapter.js` que implemente `CourierAdapter`
2. Registrar en factory (si usas factory pattern)
3. ¡Listo! No tocas nada más.

## 🔧 Migración Gradual

No necesitas cambiar todo de golpe:

**Fase 1** (1 semana):
- Crear carpeta `dto/` y mover lógica de transformación
- Crear carpeta `adapters/` y mover adaptadores

**Fase 2** (1 semana):
- Crear carpeta `core/use-cases/` y extraer lógica de services
- Refactorizar `AdmisionService` para usar use-cases

**Fase 3** (1 semana):
- Crear `core/domain/services/` y extraer lógica pura
- Reorganizar carpetas según nueva estructura

**Fase 4** (opcional):
- Agregar eventos de dominio
- Agregar CQRS si es necesario

